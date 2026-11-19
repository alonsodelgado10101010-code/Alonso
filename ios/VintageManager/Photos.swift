import PhotosUI
import SwiftUI
import UIKit

/// Fotos de prendas y comprobantes: se guardan en Application Support/photos.
enum PhotoStore {
    static var directory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func save(_ image: UIImage) -> String? {
        guard let data = image.jpegData(compressionQuality: 0.8) else { return nil }
        let url = directory.appendingPathComponent("img_\(UUID().uuidString).jpg")
        do { try data.write(to: url); return url.path } catch { return nil }
    }

    static func image(_ path: String) -> UIImage? { UIImage(contentsOfFile: path) }
}

/// Tira de fotos con botones Galería y Cámara.
struct PhotoStrip: View {
    @Binding var paths: [String]
    var max = 5
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false

    var body: some View {
        if !paths.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(paths, id: \.self) { p in
                        ZStack(alignment: .topTrailing) {
                            if let img = PhotoStore.image(p) {
                                Image(uiImage: img).resizable().scaledToFill()
                                    .frame(width: 96, height: 96).clipShape(RoundedRectangle(cornerRadius: 12))
                            }
                            Button { paths.removeAll { $0 == p } } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(Color.white, Color.black.opacity(0.6))
                            }
                            .buttonStyle(.borderless)
                            .padding(4)
                        }
                    }
                }
            }
        }
        if paths.count < max {
            HStack {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label("Galería", systemImage: "photo.on.rectangle")
                }
                .buttonStyle(.bordered)
                Spacer()
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button { showCamera = true } label: { Label("Cámara", systemImage: "camera") }
                        .buttonStyle(.bordered)
                }
            }
            .onChange(of: pickerItem) { item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let img = UIImage(data: data), let path = PhotoStore.save(img) {
                        await MainActor.run { paths.append(path) }
                    }
                    await MainActor.run { pickerItem = nil }
                }
            }
            .sheet(isPresented: $showCamera) {
                CameraPicker { img in if let path = PhotoStore.save(img) { paths.append(path) } }
                    .ignoresSafeArea()
            }
        }
    }
}

struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let img = info[.originalImage] as? UIImage { parent.onImage(img) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
