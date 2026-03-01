// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ktlint) apply false
}

ktlint {
    version.set("1.2.1")
    disabledRules.set(listOf("string-template-indent", "multiline-expression-wrapping"))
}