import SwiftUI
import AppKit
import UniformTypeIdentifiers

// TikTok SF Patcher: drop the official TikTok apk in, get TikTok SF out,
// and put it on a phone over adb. The build itself is the mod's own
// `python3 -m margyt`, shipped inside the app and run from a working copy.

struct Device: Identifiable, Hashable {
    let serial: String
    let name: String
    var id: String { serial }
}

@MainActor
final class Patcher: ObservableObject {
    @Published var apk: URL?
    @Published var log = ""
    @Published var busy = false
    @Published var phase = ""
    @Published var result: URL?
    @Published var devices: [Device] = []
    @Published var selected: String = ""
    @Published var askRemove = false

    static let package = "com.zhiliaoapp.musically"

    private let fm = FileManager.default
    private var support: URL {
        fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TikTok SF Patcher", isDirectory: true)
    }
    private var downloads: URL {
        fm.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    }

    // MARK: tools

    private var searchPath: String {
        let home = NSHomeDirectory()
        return ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin",
                home + "/Library/Android/sdk/platform-tools"].joined(separator: ":")
    }

    private func find(_ name: String) -> String? {
        for dir in searchPath.split(separator: ":") {
            let path = dir + "/" + name
            if fm.isExecutableFile(atPath: path) { return path }
        }
        return nil
    }

    private func environment() -> [String: String] {
        var env = ProcessInfo.processInfo.environment
        env["PATH"] = searchPath
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        return env
    }

    private func say(_ text: String) {
        log += text
        if log.count > 200_000 { log = String(log.suffix(150_000)) }
    }

    private func clean(_ text: String) -> String {
        text.replacingOccurrences(of: "\u{1B}\\[[0-9;]*m", with: "", options: .regularExpression)
    }

    /// Runs a program and streams what it prints. Returns the exit status and everything printed.
    private func run(_ exe: String, _ args: [String], cwd: URL? = nil, show: Bool = true) async -> (Int32, String) {
        await withCheckedContinuation { continuation in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: exe)
            process.arguments = args
            process.currentDirectoryURL = cwd
            process.environment = environment()
            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe
            var all = ""
            let lock = NSLock()
            pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
                let data = handle.availableData
                guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
                lock.lock(); all += text; lock.unlock()
                if show { Task { @MainActor in self?.say(self?.clean(text) ?? text) } }
            }
            process.terminationHandler = { done in
                pipe.fileHandleForReading.readabilityHandler = nil
                let rest = pipe.fileHandleForReading.readDataToEndOfFile()
                if let text = String(data: rest, encoding: .utf8), !text.isEmpty {
                    lock.lock(); all += text; lock.unlock()
                    if show { Task { @MainActor in self.say(self.clean(text)) } }
                }
                lock.lock(); let out = all; lock.unlock()
                continuation.resume(returning: (done.terminationStatus, out))
            }
            do { try process.run() } catch {
                continuation.resume(returning: (127, "\(error)"))
            }
        }
    }

    // MARK: choosing the apk

    func choose(_ url: URL) {
        let ext = url.pathExtension.lowercased()
        guard ext == "apk" else {
            apk = nil
            say("Нужен обычный .apk (универсальный, не XAPK/APKS/APKM): \(url.lastPathComponent)\n")
            return
        }
        apk = url
        result = nil
        say("APK: \(url.lastPathComponent)\n")
    }

    func browse() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "apk") ?? .data]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url { choose(url) }
    }

    // MARK: the build

    func build() async {
        guard let apk, !busy else { return }
        guard let python = find("python3") else {
            say("Не нашёл python3. Поставьте: xcode-select --install\n"); return
        }
        let (javaStatus, _) = await run(find("java") ?? "/usr/bin/java", ["-version"], show: false)
        guard javaStatus == 0 else {
            say("Не нашёл Java (нужна 17+). Поставьте: brew install openjdk@17\n"); return
        }
        guard let bundled = Bundle.main.resourceURL?.appendingPathComponent("mod"),
              fm.fileExists(atPath: bundled.path) else {
            say("В приложении нет исходников мода.\n"); return
        }

        busy = true; result = nil; log = ""
        defer { busy = false; phase = "" }

        phase = "Готовлю рабочую папку"
        let work = support.appendingPathComponent("mod", isDirectory: true)
        try? fm.createDirectory(at: work, withIntermediateDirectories: true)
        let rsync = "/usr/bin/rsync"
        let (c1, _) = await run(rsync, ["-a", "--delete", "--exclude", "tools/", "--exclude", "work/",
                                        "--exclude", "build/", bundled.path + "/", work.path + "/"], show: false)
        let (c2, _) = await run(rsync, ["-a", bundled.path + "/tools/", work.path + "/tools/"], show: false)
        guard c1 == 0, c2 == 0 else { say("Не удалось скопировать мод в рабочую папку.\n"); return }

        phase = "Собираю TikTok SF (первый раз 10–20 минут)"
        let out = work.appendingPathComponent("build/tiktok-sf.apk")
        try? fm.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? fm.removeItem(at: out)
        let (code, _) = await run(python, ["-m", "margyt", apk.path, "-o", out.path], cwd: work)
        guard code == 0, fm.fileExists(atPath: out.path) else {
            say("\nСборка не удалась (код \(code)). Подходит только официальный универсальный TikTok (arm64).\n"); return
        }

        let target = downloads.appendingPathComponent("TikTok SF.apk")
        try? fm.removeItem(at: target)
        do { try fm.copyItem(at: out, to: target) } catch {
            say("Не смог сохранить в Загрузки: \(error)\n"); return
        }
        result = target
        say("\nГотово: \(target.path)\n")
        await refreshDevices()
    }

    // MARK: the phone

    func refreshDevices() async {
        guard let adb = find("adb") else { devices = []; return }
        _ = await run(adb, ["start-server"], show: false)
        let (_, text) = await run(adb, ["devices", "-l"], show: false)
        var found: [Device] = []
        for line in text.split(separator: "\n").dropFirst() {
            let parts = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
            guard parts.count >= 2, parts[1] == "device" else { continue }
            let model = parts.first(where: { $0.hasPrefix("model:") })?.dropFirst(6).replacingOccurrences(of: "_", with: " ")
            found.append(Device(serial: parts[0], name: model ?? parts[0]))
        }
        devices = found
        if !found.contains(where: { $0.serial == selected }) { selected = found.first?.serial ?? "" }
    }

    func install(removeOfficial: Bool = false) async {
        guard let result, !busy else { return }
        guard let adb = find("adb") else {
            say("Не нашёл adb. Поставьте: brew install --cask android-platform-tools\n"); return
        }
        guard !selected.isEmpty else { say("Телефон не выбран. Включите отладку по USB.\n"); return }
        busy = true
        defer { busy = false; phase = "" }
        if removeOfficial {
            phase = "Удаляю официальный TikTok"
            say("\nУдаляю \(Self.package)…\n")
            _ = await run(adb, ["-s", selected, "uninstall", Self.package])
        }
        phase = "Устанавливаю на телефон"
        say("\nУстанавливаю…\n")
        let (code, text) = await run(adb, ["-s", selected, "install", "-r", result.path])
        if code == 0 && text.contains("Success") {
            say("\nУстановлено. Открывайте TikTok SF на телефоне.\n")
        } else if text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || text.contains("signatures do not match") {
            askRemove = true
        } else {
            say("\nУстановка не удалась.\n")
        }
    }

    func reveal() {
        if let result { NSWorkspace.shared.activateFileViewerSelecting([result]) }
    }
}

struct DropZone: View {
    @ObservedObject var patcher: Patcher
    @State private var over = false

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: patcher.apk == nil ? "arrow.down.doc" : "doc.zipper")
                .font(.system(size: 34))
                .foregroundStyle(.secondary)
            Text(patcher.apk?.lastPathComponent ?? "Перетащите сюда оригинальный TikTok .apk")
                .font(.headline)
                .multilineTextAlignment(.center)
            Button("Выбрать файл…") { patcher.browse() }
        }
        .frame(maxWidth: .infinity, minHeight: 150)
        .padding()
        .background(RoundedRectangle(cornerRadius: 16)
            .strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [7]))
            .foregroundStyle(over ? Color.accentColor : Color.secondary.opacity(0.5)))
        .onDrop(of: [.fileURL], isTargeted: $over) { providers in
            guard let provider = providers.first else { return false }
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url { Task { @MainActor in patcher.choose(url) } }
            }
            return true
        }
    }
}

struct ContentView: View {
    @StateObject private var patcher = Patcher()

    var body: some View {
        VStack(spacing: 14) {
            Text("TikTok SF Patcher").font(.title.bold())
            DropZone(patcher: patcher)

            Button {
                Task { await patcher.build() }
            } label: {
                Text("Сделать TikTok SF").frame(maxWidth: .infinity)
            }
            .controlSize(.large)
            .buttonStyle(.borderedProminent)
            .disabled(patcher.apk == nil || patcher.busy)

            if patcher.busy {
                HStack { ProgressView().controlSize(.small); Text(patcher.phase).foregroundStyle(.secondary) }
            }

            if patcher.result != nil {
                GroupBox {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("TikTok SF.apk в Загрузках")
                            Spacer()
                            Button("Показать") { patcher.reveal() }
                        }
                        HStack {
                            Picker("Телефон", selection: $patcher.selected) {
                                if patcher.devices.isEmpty { Text("не найден").tag("") }
                                ForEach(patcher.devices) { Text($0.name).tag($0.serial) }
                            }
                            Button { Task { await patcher.refreshDevices() } } label: {
                                Image(systemName: "arrow.clockwise")
                            }
                        }
                        Button {
                            Task { await patcher.install() }
                        } label: {
                            Text("Установить на телефон").frame(maxWidth: .infinity)
                        }
                        .disabled(patcher.busy || patcher.selected.isEmpty)
                    }
                    .padding(4)
                }
            }

            ScrollViewReader { proxy in
                ScrollView {
                    Text(patcher.log.isEmpty ? "Здесь будет журнал сборки." : patcher.log)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(patcher.log.isEmpty ? .secondary : .primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(8)
                .background(Color.black.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
                .onChange(of: patcher.log) { _ in proxy.scrollTo("end") }
            }
        }
        .padding(20)
        .task { await patcher.refreshDevices() }
        .alert("Удалить официальный TikTok?", isPresented: $patcher.askRemove) {
            Button("Удалить и установить", role: .destructive) { Task { await patcher.install(removeOfficial: true) } }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("На телефоне стоит TikTok с другой подписью, поверх него TikTok SF не встанет. Если удалить, пропадут его данные и вход в аккаунт.")
        }
    }
}

@main
struct PatcherApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView().frame(minWidth: 560, minHeight: 700)
        }
        .windowResizability(.contentMinSize)
    }
}
