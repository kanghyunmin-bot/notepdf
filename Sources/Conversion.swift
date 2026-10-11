import Foundation
import PDFKit
import Darwin

struct ConversionResult {
    let url: URL
    let pages: Int
    let warnings: String
    let log: String
}
struct ConversionFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}
final class ConversionTask {
    private let lock = NSLock()
    private var cancelled = false
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }

    func run(input: URL, folder: URL, engine: URL, mode: Int = 0, timeout: TimeInterval = 600) throws -> ConversionResult {
        guard input.pathExtension.lowercased() == "sdocx" else {
            throw ConversionFailure(message: "Samsung Notes 파일(.sdocx)을 선택해 주세요.")
        }
        let fm = FileManager.default
        guard fm.isReadableFile(atPath: input.path) else { throw ConversionFailure(message: "원본 파일을 읽을 수 없습니다.") }
        guard fm.isExecutableFile(atPath: engine.path) else { throw ConversionFailure(message: "변환 엔진이 없습니다. 앱을 다시 빌드하거나 설치해 주세요.") }
        if isCancelled { throw ConversionFailure(message: "변환이 취소되었습니다.") }
        // Staging on the destination volume makes the final, exclusive hard link atomic.
        let staging = folder.appendingPathComponent(".notepdf-" + UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { try? fm.removeItem(at: staging) }
        let pdf = staging.appendingPathComponent("result.pdf")
        let logURL = staging.appendingPathComponent("engine.log")
        fm.createFile(atPath: logURL.path, contents: nil)
        let logHandle = try FileHandle(forWritingTo: logURL)
        defer { try? logHandle.close() }
        let process = Process()
        process.executableURL = engine
        var args = ["--detailed-errors"]
        if mode == 1 { args += ["--basic-split", "iso-portrait"] }
        if mode == 2 { args += ["--auto-split"] }
        args += [input.path, pdf.path]
        process.arguments = args
        process.standardOutput = logHandle
        process.standardError = logHandle
        process.standardInput = FileHandle.nullDevice
        try process.run()
        let start = Date()
        while process.isRunning {
            if isCancelled || Date().timeIntervalSince(start) > timeout {
                process.terminate()
                for _ in 0..<20 {
                    if !process.isRunning { break }
                    Thread.sleep(forTimeInterval: 0.05)
                }
                if process.isRunning { kill(process.processIdentifier, SIGKILL) }
                process.waitUntilExit()
                throw ConversionFailure(message: isCancelled ? "변환이 취소되었습니다." : "변환 시간이 10분을 넘었습니다. 파일을 나누어 다시 시도해 주세요.")
            }
            Thread.sleep(forTimeInterval: 0.1)
        }
        process.waitUntilExit()
        try logHandle.synchronize()
        let log = (try? String(contentsOf: logURL, encoding: .utf8)) ?? ""
        guard process.terminationStatus == 0 else {
            throw ConversionFailure(message: "변환하지 못했습니다. 파일이 손상되었거나 지원하지 않는 노트 형식일 수 있습니다.\n\n" + String(log.suffix(5000)))
        }
        guard let document = PDFDocument(url: pdf), document.pageCount > 0 else {
            throw ConversionFailure(message: "유효한 PDF가 만들어지지 않았습니다.")
        }
        let count = document.pageCount
        for i in 0..<count {
            guard let page = document.page(at: i), !page.bounds(for: .mediaBox).isEmpty else {
                throw ConversionFailure(message: "PDF 페이지를 확인하지 못했습니다.")
            }
        }
        if isCancelled { throw ConversionFailure(message: "변환이 취소되었습니다.") }
        let stem = input.deletingPathExtension().lastPathComponent + "_고화질"
        var destination: URL? = nil
        for n in 0..<10000 {
            let name = stem + (n == 0 ? "" : " (\(n))") + ".pdf"
            let target = folder.appendingPathComponent(name)
            if link(pdf.path, target.path) == 0 { destination = target; break }
            if errno != EEXIST { throw ConversionFailure(message: "PDF를 저장하지 못했습니다: " + String(cString: strerror(errno))) }
        }
        guard let output = destination else { throw ConversionFailure(message: "같은 이름의 파일이 너무 많습니다.") }
        let warnings = log.split(separator: "\n").filter { $0.contains("WARN") || $0.contains("ERROR") }.joined(separator: "\n")
        return ConversionResult(url: output, pages: count, warnings: warnings, log: log)
    }
}
