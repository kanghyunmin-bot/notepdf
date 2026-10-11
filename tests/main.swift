import Foundation
import PDFKit
import AppKit

let fm = FileManager.default
let dir = fm.temporaryDirectory.appendingPathComponent("notepdf-core-tests-" + UUID().uuidString)
try fm.createDirectory(at: dir, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: dir) }
let input = dir.appendingPathComponent("한글 test.sdocx")
try Data("input preserved".utf8).write(to: input)
let sample = dir.appendingPathComponent("sample.pdf")
let doc = PDFDocument(); let page = PDFPage(); page.setBounds(NSRect(x: 0, y: 0, width: 100, height: 100), for: .mediaBox); doc.insert(page, at: 0); doc.write(to: sample)
func script(_ name: String, _ body: String) throws -> URL {
    let path = dir.appendingPathComponent(name)
    try ("#!/bin/sh\n" + body).write(to: path, atomically: true, encoding: .utf8)
    try fm.setAttributes([.posixPermissions: 0o755], ofItemAtPath: path.path)
    return path
}
let success = try script("success", "for out do :; done\n/bin/cp '\(sample.path)' \"$out\"\necho 'WARN typed text omitted' >&2\n")
let result = try ConversionTask().run(input: input, folder: dir, engine: success)
assert(result.pages == 1 && result.warnings.contains("WARN"))
let firstData = try Data(contentsOf: result.url)
let second = try ConversionTask().run(input: input, folder: dir, engine: success)
assert(second.url != result.url && second.url.lastPathComponent.contains("(1)"))
let stillOriginal = try Data(contentsOf: result.url)
assert(stillOriginal == firstData)
let sourceText = try String(contentsOf: input, encoding: .utf8)
assert(sourceText == "input preserved")
print("PASS: output validation, warning propagation, duplicate naming, original preservation")
let invalid = try script("invalid", "for out do :; done\necho 'invalid PDF' > \"$out\"\n")
do { _ = try ConversionTask().run(input: input, folder: dir, engine: invalid); fatalError("invalid accepted") } catch { print("PASS: invalid PDF rejected") }
let failure = try script("failure", "echo 'broken source' >&2\nexit 1\n")
do { _ = try ConversionTask().run(input: input, folder: dir, engine: failure); fatalError("failure accepted") } catch { assert(error.localizedDescription.contains("broken source")); print("PASS: failure details preserved") }
let slow = try script("slow", "exec /bin/sleep 30\n")
let task = ConversionTask()
DispatchQueue.global().asyncAfter(deadline: .now()+0.3) { task.cancel() }
let start = Date()
do { _ = try task.run(input: input, folder: dir, engine: slow); fatalError("cancel ignored") } catch { assert(task.isCancelled && Date().timeIntervalSince(start) < 3); print("PASS: active cancellation") }
do { _ = try ConversionTask().run(input: input, folder: dir, engine: slow, timeout: 0.2); fatalError("timeout ignored") } catch { assert(error.localizedDescription.contains("시간")); print("PASS: timeout") }
let leftovers = try fm.contentsOfDirectory(atPath: dir.path).filter { $0.hasPrefix(".notepdf-") }
assert(leftovers.isEmpty)
print("PASS: staging cleaned for every success/failure/cancel")
