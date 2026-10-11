import AppKit
import PDFKit
import UniformTypeIdentifiers

let ink = NSColor(calibratedRed: 0.08, green: 0.14, blue: 0.23, alpha: 1)
let teal = NSColor(calibratedRed: 0.00, green: 0.48, blue: 0.46, alpha: 1)
func label(_ text: String, _ size: CGFloat = 13, _ weight: NSFont.Weight = .regular, _ color: NSColor = ink) -> NSTextField {
    let v = NSTextField(labelWithString: text)
    v.font = .systemFont(ofSize: size, weight: weight); v.textColor = color
    v.lineBreakMode = .byTruncatingMiddle
    return v
}
func button(_ title: String, _ target: AnyObject, _ action: Selector) -> NSButton {
    let b = NSButton(title: title, target: target, action: action)
    b.bezelStyle = .rounded; b.controlSize = .large
    return b
}
final class DropView: NSView {
    var onFiles: (([URL]) -> Void)?
    var onLayout: (() -> Void)?
    override init(frame: NSRect) { super.init(frame: frame); registerForDraggedTypes([.fileURL]) }
    required init?(coder: NSCoder) { fatalError() }
    override func layout() { super.layout(); onLayout?() }
    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        return urls.contains { $0.pathExtension.lowercased() == "sdocx" } ? .copy : []
    }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        onFiles?(urls); return !urls.isEmpty
    }
}
final class Item {
    let input: URL
    var status = "대기 중"
    var result: ConversionResult?
    var error: String?
    var finished = false
    init(_ url: URL) { input = url }
}
final class AppDelegate: NSObject, NSApplicationDelegate, NSTableViewDataSource, NSTableViewDelegate {
    var window: NSWindow!
    let root = DropView(frame: .zero)
    let table = NSTableView()
    let scroll = NSScrollView()
    let preview = PDFView()
    let left = NSView(), right = NSView()
    let icon = NSImageView()
    let titleLabel = label("NotePDF", 30, .bold)
    let subtitle = label("삼성 노트를, 선명한 PDF로.", 14, .medium, .secondaryLabelColor)
    let badge = label("내 컴퓨터에서만 변환", 12, .medium, teal)
    let listTitle = label("변환할 노트", 17, .semibold)
    let countLabel = label("0개", 12, .medium, .secondaryLabelColor)
    let previewTitle = label("PDF 미리보기", 17, .semibold)
    let previewInfo = label("변환한 파일을 선택하면 여기에 표시됩니다.", 12, .regular, .secondaryLabelColor)
    let empty = label(".sdocx 파일을 끌어놓으세요\n\n또는 아래 ‘파일 추가’를 눌러 선택하세요.", 15, .medium, .secondaryLabelColor)
    let previewEmpty = label("필기 선은 또렷하게,\n노트 배치는 그대로.", 24, .medium, .tertiaryLabelColor)
    let note = label("필기·도형을 벡터로 저장합니다. 삽입 이미지는 원래 해상도를 유지합니다.\n타이핑 글자·일부 펜 효과 등은 누락되거나 다르게 보일 수 있습니다. 변환 후 미리보기를 확인해 주세요.", 11, .regular, .secondaryLabelColor)
    let status = label("파일을 추가해 시작하세요.", 12, .medium, .secondaryLabelColor)
    let modeLabel = label("긴 노트 처리", 12, .medium)
    let mode = NSPopUpButton()
    let folderLabel = label("저장: 원본 파일 옆", 11, .regular, .secondaryLabelColor)
    let progress = NSProgressIndicator()
    var addButton: NSButton!, removeButton: NSButton!, startButton: NSButton!, cancelButton: NSButton!
    var folderButton: NSButton!, resetFolderButton: NSButton!, revealButton: NSButton!, logButton: NSButton!
    var items: [Item] = []
    var folder: URL?
    var running = false
    var currentTask: ConversionTask?
    var stopRequested = false
    var batchFolder: URL?
    var batchMode = 0
    var pending: [URL] = []
    var engine: URL { Bundle.main.resourceURL!.appendingPathComponent("sdocx2pdf") }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        buildMenu(); buildWindow()
        if !pending.isEmpty { add(pending); pending.removeAll() }
        NSApp.activate(ignoringOtherApps: true)
    }
    func application(_ application: NSApplication, open urls: [URL]) {
        if window == nil { pending += urls } else { add(urls); window.makeKeyAndOrderFront(nil) }
    }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        window?.makeKeyAndOrderFront(nil); return true
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if running {
            stopRequested = true; currentTask?.cancel()
            status.stringValue = "변환을 취소하고 종료합니다…"
            quitAfterCancel = true
            return .terminateLater
        }
        return .terminateNow
    }
    var quitAfterCancel = false
    func buildMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem(); main.addItem(appItem)
        let appMenu = NSMenu(); appItem.submenu = appMenu
        appMenu.addItem(withTitle: "NotePDF 정보", action: #selector(about), keyEquivalent: "").target = self
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "NotePDF 종료", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let fileItem = NSMenuItem(); main.addItem(fileItem)
        let file = NSMenu(title: "파일"); fileItem.submenu = file
        file.addItem(withTitle: "파일 추가…", action: #selector(pickFiles), keyEquivalent: "o").target = self
        file.addItem(withTitle: "창 닫기", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        NSApp.mainMenu = main
    }
    func buildWindow() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1120, height: 800), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "NotePDF"; window.minSize = NSSize(width: 1000, height: 760)
        window.isReleasedWhenClosed = false
        window.backgroundColor = NSColor(calibratedRed: 0.95, green: 0.96, blue: 0.965, alpha: 1)
        window.appearance = NSAppearance(named: .aqua)
        window.contentView = root
        root.onFiles = { [weak self] in self?.add($0) }
        root.onLayout = { [weak self] in self?.layout() }
        for card in [left, right] { card.wantsLayer = true; card.layer?.backgroundColor = NSColor.white.cgColor; card.layer?.cornerRadius = 18; root.addSubview(card) }
        for v in [icon, titleLabel, subtitle, badge, note, status, progress] as [NSView] { root.addSubview(v) }
        icon.image = NSImage(contentsOf: Bundle.main.resourceURL!.appendingPathComponent("Logo.png"))
        icon.imageScaling = .scaleProportionallyUpOrDown
        empty.maximumNumberOfLines = 4; empty.alignment = .center
        previewEmpty.maximumNumberOfLines = 3; previewEmpty.alignment = .center
        note.maximumNumberOfLines = 3
        addButton = button("＋ 파일 추가", self, #selector(pickFiles))
        removeButton = button("선택 제거", self, #selector(removeSelected))
        folderButton = button("저장 폴더…", self, #selector(pickFolder))
        resetFolderButton = button("원본 옆", self, #selector(resetFolder))
        startButton = button("PDF로 변환", self, #selector(start)); startButton.contentTintColor = teal
        cancelButton = button("취소", self, #selector(cancel)); cancelButton.isHidden = true
        revealButton = button("Finder에서 보기", self, #selector(reveal))
        logButton = button("변환 내역", self, #selector(showLog))
        for v in [listTitle, countLabel, scroll, empty, addButton!, removeButton!, modeLabel, mode, folderLabel, folderButton!, resetFolderButton!] { left.addSubview(v) }
        for v in [previewTitle, previewInfo, preview, previewEmpty, revealButton!, logButton!] { right.addSubview(v) }
        root.addSubview(startButton); root.addSubview(cancelButton)
        mode.addItems(withTitles: ["원래 길이 유지", "A4 세로로 나누기", "삽입 PDF의 페이지에 맞추기"])
        mode.toolTip = "페이지 구분이 없는 긴 노트에만 적용됩니다. 이미 나뉜 페이지는 유지합니다."
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("file")); column.width = 342
        table.addTableColumn(column); table.headerView = nil; table.rowHeight = 62
        table.delegate = self; table.dataSource = self; table.allowsMultipleSelection = true
        table.backgroundColor = .white; table.selectionHighlightStyle = .regular
        table.columnAutoresizingStyle = .lastColumnOnlyAutoresizingStyle
        scroll.documentView = table; scroll.hasVerticalScroller = true; scroll.drawsBackground = false
        preview.autoScales = true; preview.displayMode = .singlePageContinuous
        preview.backgroundColor = NSColor(calibratedWhite: 0.97, alpha: 1)
        progress.style = .spinning; progress.controlSize = .small; progress.isDisplayedWhenStopped = false
        layout(); refresh(); window.center(); window.makeKeyAndOrderFront(nil)
    }
    func layout() {
        let w = root.bounds.width, h = root.bounds.height
        icon.frame = NSRect(x: 28, y: h-98, width: 64, height: 64)
        titleLabel.frame = NSRect(x: 108, y: h-65, width: 240, height: 38)
        subtitle.frame = NSRect(x: 110, y: h-91, width: 420, height: 24)
        badge.frame = NSRect(x: w-230, y: h-68, width: 200, height: 24)
        left.frame = NSRect(x: 28, y: 146, width: 380, height: h-266)
        right.frame = NSRect(x: 424, y: 146, width: w-452, height: h-266)
        let lh = left.bounds.height, rw = right.bounds.width
        listTitle.frame = NSRect(x: 20, y: lh-46, width: 240, height: 26)
        countLabel.frame = NSRect(x: 314, y: lh-43, width: 48, height: 20)
        scroll.frame = NSRect(x: 14, y: 166, width: 352, height: max(40,lh-228))
        empty.frame = NSRect(x: 20, y: 210, width: 340, height: max(80,lh-310))
        addButton.frame = NSRect(x: 16, y: 126, width: 190, height: 34)
        removeButton.frame = NSRect(x: 222, y: 126, width: 140, height: 34)
        modeLabel.frame = NSRect(x: 22, y: 92, width: 96, height: 20)
        mode.frame = NSRect(x: 114, y: 88, width: 250, height: 28)
        folderLabel.frame = NSRect(x: 22, y: 61, width: 335, height: 20)
        folderButton.frame = NSRect(x: 16, y: 18, width: 220, height: 34)
        resetFolderButton.frame = NSRect(x: 246, y: 18, width: 116, height: 34)
        previewTitle.frame = NSRect(x: 20, y: lh-46, width: rw-40, height: 26)
        previewInfo.frame = NSRect(x: 20, y: lh-74, width: rw-40, height: 22)
        preview.frame = NSRect(x: 16, y: 62, width: rw-32, height: max(50,lh-146))
        previewEmpty.frame = NSRect(x: 20, y: lh/2-50, width: rw-40, height: 100)
        revealButton.frame = NSRect(x: rw-184, y: 16, width: 170, height: 34)
        logButton.frame = NSRect(x: 14, y: 16, width: 130, height: 34)
        note.frame = NSRect(x: 32, y: 76, width: w-64, height: 52)
        progress.frame = NSRect(x: 32, y: 28, width: 20, height: 20)
        status.frame = NSRect(x: 60, y: 26, width: w-370, height: 24)
        startButton.frame = NSRect(x: w-206, y: 20, width: 178, height: 38)
        cancelButton.frame = NSRect(x: w-300, y: 20, width: 86, height: 38)
    }
    func add(_ urls: [URL]) {
        guard !running else { return }
        let supported = urls.filter { $0.pathExtension.lowercased() == "sdocx" && $0.isFileURL }
        for url in supported {
            let u = url.standardizedFileURL
            if !items.contains(where: { $0.input == u }) { items.append(Item(u)) }
        }
        status.stringValue = supported.isEmpty ? "Samsung Notes에서 내보낸 .sdocx 파일을 선택해 주세요." : "\(items.count)개의 노트를 준비했습니다."
        refresh()
    }
    @objc func pickFiles() {
        guard !running else { return }
        let panel = NSOpenPanel(); panel.allowsMultipleSelection = true
        panel.allowedContentTypes = [UTType(filenameExtension: "sdocx") ?? .data]
        panel.canChooseDirectories = false
        panel.beginSheetModal(for: window) { [weak self] result in if result == .OK { self?.add(panel.urls) } }
    }
    @objc func pickFolder() {
        let p = NSOpenPanel(); p.canChooseDirectories = true; p.canChooseFiles = false; p.canCreateDirectories = true
        p.beginSheetModal(for: window) { [weak self] r in if r == .OK { self?.folder = p.url; self?.refresh() } }
    }
    @objc func resetFolder() { folder = nil; refresh() }
    @objc func removeSelected() {
        guard !running else { return }
        for i in table.selectedRowIndexes.reversed() { items.remove(at: i) }
        preview.document = nil; refresh(); updatePreview()
    }
    func refresh() {
        let selection = table.selectedRowIndexes.filter { items.indices.contains($0) }
        table.reloadData()
        if !selection.isEmpty { table.selectRowIndexes(IndexSet(selection), byExtendingSelection: false) }
        else if !items.isEmpty { table.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false) }
        countLabel.stringValue = "\(items.count)개"; empty.isHidden = !items.isEmpty
        addButton.isEnabled = !running; removeButton.isEnabled = !running && !items.isEmpty
        folderButton.isEnabled = !running; resetFolderButton.isEnabled = !running; mode.isEnabled = !running
        startButton.isEnabled = !running && items.contains { !$0.finished }
        cancelButton.isHidden = !running
        folderLabel.stringValue = "저장: " + (folder?.path ?? "원본 파일 옆")
        folderLabel.toolTip = folder?.path
        if running { progress.startAnimation(nil) } else { progress.stopAnimation(nil) }
        updatePreview()
    }
    func numberOfRows(in tableView: NSTableView) -> Int { items.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let item = items[row], view = NSTableCellView()
        let title = label(item.input.lastPathComponent, 13, .medium)
        title.frame = NSRect(x: 10, y: 32, width: 320, height: 22); title.toolTip = item.input.path
        let detail = label(item.status, 11, .regular, item.error != nil ? .systemRed : (item.finished ? teal : .secondaryLabelColor))
        detail.frame = NSRect(x: 10, y: 10, width: 320, height: 20)
        view.addSubview(title); view.addSubview(detail); return view
    }
    func tableViewSelectionDidChange(_ notification: Notification) { updatePreview() }
    var selected: Item? { let row = table.selectedRow; return items.indices.contains(row) ? items[row] : nil }
    func updatePreview() {
        let item = selected
        if let result = item?.result {
            if preview.document?.documentURL != result.url { preview.document = PDFDocument(url: result.url) }
            previewInfo.stringValue = "\(result.pages)페이지 · " + (result.warnings.isEmpty ? "변환 완료" : "일부 항목 확인 필요 · 변환 내역 참고")
        } else {
            preview.document = nil
            previewInfo.stringValue = item?.error != nil ? "변환 내역에서 오류를 확인할 수 있습니다." : "변환한 파일을 선택하면 여기에 표시됩니다."
        }
        previewEmpty.isHidden = preview.document != nil
        revealButton.isEnabled = item?.result != nil
        logButton.isEnabled = item?.result != nil || item?.error != nil
    }
    @objc func start() {
        guard !running else { return }
        running = true; stopRequested = false; batchFolder = folder; batchMode = mode.indexOfSelectedItem
        for item in items where !item.finished { item.error = nil; item.status = "대기 중" }
        refresh(); next()
    }
    func next() {
        guard !stopRequested, let index = items.firstIndex(where: { !$0.finished && $0.error == nil }) else {
            running = false; currentTask = nil
            let done = items.filter { $0.finished }.count, failed = items.filter { $0.error != nil }.count
            status.stringValue = stopRequested ? "취소했습니다. 완료된 PDF는 그대로 보관됩니다." : "완료 \(done)개" + (failed > 0 ? " · 실패 \(failed)개 — 선택 후 변환 내역을 확인하세요." : " · 원본 파일은 보존했습니다.")
            refresh()
            if quitAfterCancel { NSApp.reply(toApplicationShouldTerminate: true) }
            return
        }
        let item = items[index], task = ConversionTask()
        currentTask = task; item.status = "변환 중…"
        status.stringValue = "\(index+1)/\(items.count) · \(item.input.lastPathComponent) 변환 중"
        refresh(); table.selectRowIndexes(IndexSet(integer: index), byExtendingSelection: false)
        let destination = batchFolder ?? item.input.deletingLastPathComponent(), engineURL = engine, split = batchMode
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = Result { try task.run(input: item.input, folder: destination, engine: engineURL, mode: split) }
            DispatchQueue.main.async {
                guard let self = self else { return }
                switch result {
                case .success(let value):
                    item.result = value; item.finished = true
                    item.status = "\(value.pages)페이지 · " + (value.warnings.isEmpty ? "완료" : "완료 · 일부 항목 확인 필요")
                case .failure(let error):
                    item.error = error.localizedDescription
                    item.status = task.isCancelled ? "취소됨 · 다시 시도 가능" : "실패 · 변환 내역 확인"
                }
                self.refresh(); self.next()
            }
        }
    }
    @objc func cancel() { stopRequested = true; currentTask?.cancel(); status.stringValue = "취소 중…" }
    @objc func reveal() { if let url = selected?.result?.url { NSWorkspace.shared.activateFileViewerSelecting([url]) } }
    @objc func showLog() {
        guard let item = selected else { return }
        let alert = NSAlert(); alert.messageText = item.error == nil ? "변환 내역" : "변환하지 못했습니다"
        alert.informativeText = item.result?.warnings.isEmpty == false ? "일부 요소가 생략되거나 다르게 표현됐을 수 있습니다. 원본과 미리보기를 비교해 주세요." : "변환 결과 및 엔진 기록입니다."
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 620, height: 280)); scroll.hasVerticalScroller = true
        let text = NSTextView(frame: scroll.bounds); text.isEditable = false; text.isSelectable = true
        text.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        text.string = item.error ?? item.result?.log ?? ""
        text.autoresizingMask = [.width]; text.isVerticallyResizable = true
        scroll.documentView = text; alert.accessoryView = scroll; alert.beginSheetModal(for: window)
    }
    @objc func about() {
        let a = NSAlert(); a.messageText = "NotePDF 1.0 RC1"
        a.informativeText = "Samsung Notes .sdocx → 벡터 PDF\n파일을 외부로 전송하지 않고 Mac 안에서 변환합니다.\n\n변환 엔진: sdocx2pdf 0.4.0 (MIT)\nhttps://github.com/squ1dd13/sdocx2pdf\n\nSamsung과 관계없는 독립 앱입니다."
        a.runModal()
    }
}

if CommandLine.arguments.count > 1 && CommandLine.arguments[1] == "--convert-test" {
    let args = CommandLine.arguments
    guard args.count >= 4 else { fputs("Usage: --convert-test input.sdocx output-folder [mode]\n", stderr); exit(2) }
    do {
        let result = try ConversionTask().run(input: URL(fileURLWithPath: args[2]), folder: URL(fileURLWithPath: args[3]), engine: Bundle.main.resourceURL!.appendingPathComponent("sdocx2pdf"), mode: args.count > 4 ? Int(args[4]) ?? 0 : 0)
        print("\(result.pages) pages\n\(result.url.path)\n\(result.warnings)")
        exit(0)
    } catch { fputs(error.localizedDescription + "\n", stderr); exit(1) }
}
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
