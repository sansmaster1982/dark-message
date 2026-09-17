import SwiftUI

struct SettingsView: View {

    @EnvironmentObject var settings: SettingsStore
    @State private var showInstructions = false

    var body: some View {
        NavigationView {
            List {
                // Theme
                Section(header: Text(L("settings_theme"))) {
                    Picker(L("settings_theme"), selection: $settings.themeMode) {
                        Text(L("settings_theme_dark")).tag(ThemeMode.dark)
                        Text(L("settings_theme_light")).tag(ThemeMode.light)
                        Text(L("settings_theme_system")).tag(ThemeMode.system)
                    }
                    .pickerStyle(.segmented)
                }

                // Language
                Section(header: Text(L("settings_language"))) {
                    Picker(L("settings_language"), selection: $settings.language) {
                        ForEach(AppLanguage.allCases, id: \.self) { lang in
                            Text(lang.displayName).tag(lang)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                // How to use
                Section {
                    Button {
                        showInstructions = true
                    } label: {
                        Label(L("settings_howto"), systemImage: "questionmark.circle")
                    }
                }

                // About
                Section(header: Text(L("settings_about"))) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("settings_version"))
                            .font(.headline)
                        Text(L("settings_description"))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)

                    Label(L("settings_encryption"), systemImage: "lock.shield")
                        .font(.subheadline)
                    Label(L("settings_kdf"), systemImage: "key")
                        .font(.subheadline)
                    Label(L("settings_no_network"), systemImage: "wifi.slash")
                        .font(.subheadline)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings_supported_docs"))
                            .font(.subheadline.bold())
                        Text(L("settings_doc_formats"))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle(L("settings_title"))
            .sheet(isPresented: $showInstructions) {
                InstructionsView()
            }
        }
    }
}

// MARK: - Instructions

struct InstructionsView: View {

    @Environment(\.dismiss) private var dismiss

    private let sections: [(titleKey: String, steps: [String])] = [
        ("settings_instr_overview_title", ["settings_instr_overview"]),
        ("settings_instr_step1_title", [
            "settings_instr_step1_1", "settings_instr_step1_2", "settings_instr_step1_3",
            "settings_instr_step1_4", "settings_instr_step1_5", "settings_instr_step1_6",
            "qr_howto_step"
        ]),
        ("settings_instr_step2_title", [
            "settings_instr_step2_1", "settings_instr_step2_2", "settings_instr_step2_3",
            "settings_instr_step2_4", "settings_instr_step2_5", "settings_instr_step2_6",
            "settings_instr_step2_7", "settings_instr_step2_8", "settings_instr_step2_9"
        ]),
        ("settings_instr_step3_title", [
            "settings_instr_step3_1", "settings_instr_step3_2", "settings_instr_step3_3"
        ]),
        ("settings_instr_step4_title", [
            "settings_instr_step4_1", "settings_instr_step4_2", "settings_instr_step4_3",
            "settings_instr_step4_4", "settings_instr_step4_5", "settings_instr_step4_6",
            "settings_instr_step4_7"
        ]),
        ("settings_instr_tips_title", [
            "settings_instr_tip1", "settings_instr_tip2", "settings_instr_tip3",
            "settings_instr_tip4", "settings_instr_tip5"
        ])
    ]

    var body: some View {
        NavigationView {
            List {
                ForEach(sections, id: \.titleKey) { section in
                    Section(header: Text(L(section.titleKey))) {
                        ForEach(section.steps, id: \.self) { stepKey in
                            Text(L(stepKey))
                                .font(.subheadline)
                                .padding(.vertical, 2)
                        }
                    }
                }
            }
            .navigationTitle(L("settings_instr_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) {
                        dismiss()
                    }
                }
            }
        }
    }
}
