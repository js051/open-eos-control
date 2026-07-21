import SwiftUI

struct LanguageSettingsView: View {
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                languageRow(.system, title: "language_system")
                languageRow(.english, title: "language_english")
                languageRow(.traditionalChinese, title: "language_traditional_chinese")
            }
            .scrollContentBackground(.hidden)
            .background(Color.cameraSurface)
            .navigationTitle(Text("language"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private func languageRow(_ value: AppLanguage, title: LocalizedStringKey) -> some View {
        Button {
            language.select(value)
        } label: {
            HStack {
                Text(title).foregroundStyle(Color.cameraText)
                Spacer()
                if language.selection == value {
                    Image(systemName: "checkmark").foregroundStyle(Color.cameraAccent)
                }
            }
            .frame(minHeight: 44)
        }
        .listRowBackground(Color.cameraSurfaceRaised)
    }
}
