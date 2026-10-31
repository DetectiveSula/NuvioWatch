// Nuvio Watch helper for Mac.
//
// Nuvio Desktop reports what it's playing to Discord ("Rich Presence") over a local
// socket. This helper answers on that socket the way Discord would, and forwards
// play / pause / stop to the family's Nuvio Watch channel, encrypted the same way
// the Android app does it. It never talks to Discord or to Nuvio's servers.

import CryptoKit
import Foundation

// MARK: - Settings

struct Config: Codable {
    var setupCode: String        // the Nuvio account id, shown in Nuvio Watch → Settings → Mac
    var deviceId: String
    var deviceName: String
    var profileIndex: Int?
}

let supportDir = FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("Library/Application Support/NuvioWatch", isDirectory: true)
let configURL = supportDir.appendingPathComponent("config.json")

func log(_ message: String) {
    let stamp = ISO8601DateFormatter().string(from: Date())
    FileHandle.standardError.write("[\(stamp)] \(message)\n".data(using: .utf8)!)
}

func loadConfig() -> Config? {
    guard let data = try? Data(contentsOf: configURL) else { return nil }
    return try? JSONDecoder().decode(Config.self, from: data)
}

// MARK: - Family channel (same scheme as LiveChannel in the Android app)

struct LiveEvent: Codable {
    let deviceId: String
    let deviceName: String
    let profileIndex: Int?
    let title: String
    let subtitle: String?
    let state: String           // PLAYING, PAUSED or STOPPED
    let positionMs: Int64
    let durationMs: Int64
    let at: Int64
}

final class Channel {
    let topic: String
    private let key: SymmetricKey

    init(userId: String) {
        let topicHash = SHA256.hash(data: Data("nuvio-watch-topic:\(userId)".utf8))
        topic = "nw-" + topicHash.map { String(format: "%02x", $0) }.joined().prefix(32)
        key = SymmetricKey(data: Data(SHA256.hash(data: Data("nuvio-watch-key:\(userId)".utf8))))
    }

    /// base64(nonce ‖ ciphertext ‖ tag), matching AES/GCM/NoPadding with a 12-byte IV.
    func encrypt(_ text: String) throws -> String {
        let sealed = try AES.GCM.seal(Data(text.utf8), using: key)
        guard let combined = sealed.combined else { throw NSError(domain: "NuvioWatch", code: 1) }
        return combined.base64EncodedString()
    }

    func publish(_ event: LiveEvent) {
        guard let json = try? JSONEncoder().encode(event),
              let body = try? encrypt(String(decoding: json, as: UTF8.self)),
              let url = URL(string: "https://ntfy.sh/\(topic)") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = Data(body.utf8)
        request.setValue("text/plain", forHTTPHeaderField: "Content-Type")
        URLSession.shared.dataTask(with: request) { _, response, error in
            if let error = error {
                log("send failed: \(error.localizedDescription)")
            } else if let http = response as? HTTPURLResponse, http.statusCode >= 300 {
                log("send failed: relay returned \(http.statusCode)")
            } else {
                log("sent \(event.state.lowercased()) · \(event.title)")
            }
        }.resume()
    }
}

// MARK: - What Nuvio Desktop is doing

final class PlaybackState {
    private let config: Config
    private let channel: Channel
    private let lock = NSLock()
    private var last: LiveEvent?

    init(config: Config, channel: Channel) {
        self.config = config
        self.channel = channel
        // While playing, report every minute so other devices know it's still going.
        Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in self?.heartbeat() }
    }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Nuvio Desktop sends "Watching: <title>" / "Paused: <title>" while the player is open.
    func activityChanged(_ activity: [String: Any]?) {
        let details = activity?["details"] as? String ?? ""
        let stateLine = activity?["state"] as? String
        let start = (activity?["timestamps"] as? [String: Any])?["start"] as? NSNumber

        var state = "STOPPED"
        var title = ""
        if details.hasPrefix("Watching: ") {
            state = "PLAYING"
            title = String(details.dropFirst("Watching: ".count))
        } else if details.hasPrefix("Paused: ") {
            state = "PAUSED"
            title = String(details.dropFirst("Paused: ".count))
        }
        let subtitle = (stateLine == "Watching" || stateLine == "Paused") ? nil : stateLine

        lock.lock()
        defer { lock.unlock() }
        if state == "STOPPED" {
            guard let previous = last, previous.state != "STOPPED" else { return }
            send(copy(previous, state: "STOPPED", position: previous.positionMs))
            return
        }
        var position = last?.title == title ? last?.positionMs ?? 0 : 0
        if state == "PLAYING", let start = start {
            position = max(0, now() - start.int64Value * 1000)
        }
        let event = LiveEvent(
            deviceId: config.deviceId,
            deviceName: config.deviceName,
            profileIndex: config.profileIndex,
            title: title,
            subtitle: subtitle,
            state: state,
            positionMs: position,
            durationMs: 0,
            at: now()
        )
        if let previous = last, previous.state == event.state, previous.title == event.title, previous.subtitle == event.subtitle {
            last = event // just a seek: remember the new position without sending
            return
        }
        send(event)
    }

    /// Nuvio closed the connection (quit or turned Rich Presence off).
    func disconnected() { activityChanged(nil) }

    private func heartbeat() {
        lock.lock()
        defer { lock.unlock() }
        guard let previous = last, previous.state == "PLAYING" else { return }
        let elapsed = now() - previous.at
        send(copy(previous, state: "PLAYING", position: previous.positionMs + elapsed))
    }

    private func copy(_ e: LiveEvent, state: String, position: Int64) -> LiveEvent {
        LiveEvent(deviceId: e.deviceId, deviceName: e.deviceName, profileIndex: e.profileIndex, title: e.title,
                  subtitle: e.subtitle, state: state, positionMs: position, durationMs: e.durationMs, at: now())
    }

    private func send(_ event: LiveEvent) {
        last = event
        channel.publish(event)
    }
}

// MARK: - Discord-style local socket

let opHandshake: UInt32 = 0
let opFrame: UInt32 = 1
let opClose: UInt32 = 2
let opPing: UInt32 = 3
let opPong: UInt32 = 4

func readExactly(_ fd: Int32, _ count: Int) -> Data? {
    var data = Data(count: count)
    var offset = 0
    while offset < count {
        let n = data.withUnsafeMutableBytes { buf in
            read(fd, buf.baseAddress!.advanced(by: offset), count - offset)
        }
        if n <= 0 { return nil }
        offset += n
    }
    return data
}

func readFrame(_ fd: Int32) -> (UInt32, Data)? {
    guard let header = readExactly(fd, 8) else { return nil }
    let opcode = header.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt32.self) }.littleEndian
    let length = Int(header.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 4, as: UInt32.self) }.littleEndian)
    guard length <= 1_048_576 else { return nil }
    if length == 0 { return (opcode, Data()) }
    guard let payload = readExactly(fd, length) else { return nil }
    return (opcode, payload)
}

func writeFrame(_ fd: Int32, _ opcode: UInt32, _ object: [String: Any]) {
    guard let payload = try? JSONSerialization.data(withJSONObject: object) else { return }
    var header = Data()
    var op = opcode.littleEndian
    var len = UInt32(payload.count).littleEndian
    header.append(Data(bytes: &op, count: 4))
    header.append(Data(bytes: &len, count: 4))
    let frame = header + payload
    _ = frame.withUnsafeBytes { write(fd, $0.baseAddress!, frame.count) }
}

func serve(_ fd: Int32, playback: PlaybackState) {
    defer {
        close(fd)
        playback.disconnected()
    }
    while let (opcode, payload) = readFrame(fd) {
        let json = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] ?? [:]
        switch opcode {
        case opHandshake:
            writeFrame(fd, opFrame, [
                "cmd": "DISPATCH",
                "evt": "READY",
                "data": ["v": 1, "user": ["id": "0", "username": "Nuvio Watch", "discriminator": "0"] as [String: Any]] as [String: Any],
            ])
        case opFrame:
            let nonce = json["nonce"] as? String ?? ""
            let cmd = json["cmd"] as? String ?? ""
            writeFrame(fd, opFrame, ["cmd": cmd, "nonce": nonce, "evt": NSNull(), "data": [String: Any]()])
            if cmd == "SET_ACTIVITY" {
                let args = json["args"] as? [String: Any]
                playback.activityChanged(args?["activity"] as? [String: Any])
            }
        case opPing:
            writeFrame(fd, opPong, json)
        case opClose:
            return
        default:
            continue
        }
    }
}

/// Listens where Nuvio Desktop looks for Discord: $TMPDIR/discord-ipc-0…9.
func listen(playback: PlaybackState) -> Never {
    let dir = NSTemporaryDirectory()
    var listenFd: Int32 = -1
    var chosen = ""
    for i in 0...9 {
        let path = (dir as NSString).appendingPathComponent("discord-ipc-\(i)")
        if FileManager.default.fileExists(atPath: path) {
            // Taken by Discord (or a stale socket from a crash): only reuse it if nobody answers.
            if canConnect(path) { continue }
            unlink(path)
        }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else { close(fd); continue }
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            raw.copyBytes(from: bytes)
            raw[bytes.count] = 0
        }
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        if bound == 0 && Darwin.listen(fd, 4) == 0 {
            listenFd = fd
            chosen = path
            break
        }
        close(fd)
    }
    guard listenFd >= 0 else {
        log("couldn't open a discord-ipc socket")
        exit(1)
    }
    if chosen.hasSuffix("-0") == false {
        log("note: Discord seems to be running, so Nuvio may talk to Discord instead of this helper")
    }
    log("listening on \(chosen)")
    while true {
        let client = accept(listenFd, nil, nil)
        if client < 0 { continue }
        Thread.detachNewThread { serve(client, playback: playback) }
    }
}

func canConnect(_ path: String) -> Bool {
    let fd = socket(AF_UNIX, SOCK_STREAM, 0)
    defer { close(fd) }
    var addr = sockaddr_un()
    addr.sun_family = sa_family_t(AF_UNIX)
    let bytes = Array(path.utf8)
    guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else { return false }
    withUnsafeMutableBytes(of: &addr.sun_path) { raw in
        raw.copyBytes(from: bytes)
        raw[bytes.count] = 0
    }
    return withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
    } == 0
}

// MARK: - Main

guard let config = loadConfig() else {
    log("no config at \(configURL.path); run install.sh with your setup code")
    exit(1)
}
signal(SIGPIPE, SIG_IGN)
let playback = PlaybackState(config: config, channel: Channel(userId: config.setupCode))
Thread.detachNewThread { listen(playback: playback) }
RunLoop.main.run()
