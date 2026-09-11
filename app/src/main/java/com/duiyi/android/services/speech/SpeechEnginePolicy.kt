package com.duiyi.android.services.speech

object SpeechEnginePolicy {
    fun isHonorFamily(manufacturer: String, brand: String): Boolean {
        val blob = "$manufacturer $brand".lowercase()
        return blob.contains("honor") || blob.contains("huawei")
    }

    fun enableLanguageSwitch(manufacturer: String, brand: String): Boolean {
        val maker = manufacturer.lowercase()
        return maker == "google" || maker.contains("samsung")
    }

    fun hasSpeechPacks(installedLocales: Collection<String>): Boolean =
        installedLocales.any { tag ->
            val lower = tag.lowercase()
            lower.contains("zh") || lower.contains("en")
        }

    fun useOnDeviceRecognizer(
        honorFamily: Boolean,
        onDeviceAvailable: Boolean,
        installedLocales: Collection<String>,
    ): Boolean = !honorFamily && onDeviceAvailable && hasSpeechPacks(installedLocales)

    fun preferOffline(
        honorFamily: Boolean,
        usingOnDevice: Boolean,
        packsInstalled: Boolean,
    ): Boolean {
        if (honorFamily) return false
        return usingOnDevice || packsInstalled
    }

    fun isGoogleSpeechPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p == "com.google.android.googlequicksearchbox" ||
            p.startsWith("com.google.android.googlequicksearchbox.") ||
            p == "com.google.android.gms"
    }

    fun isVendorMuteSpeechPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return listOf(
            "hihonor",
            "huawei",
            "honor",
            "vassistant",
            "hivoice",
            "celia",
            "hiassistant",
        ).any { token -> p.contains(token) }
    }

    fun rankSpeechPackages(packages: List<String>, honorFamily: Boolean): List<String> {
        val google = packages.filter { isGoogleSpeechPackage(it) }
        val other = packages.filter { !isGoogleSpeechPackage(it) && !isVendorMuteSpeechPackage(it) }
        val vendor = packages.filter { isVendorMuteSpeechPackage(it) }
        return if (honorFamily) google else google + other + vendor
    }

    fun useSilenceWindowExtras(honorFamily: Boolean): Boolean = !honorFamily

    fun useGoogleSpeechIntent(honorFamily: Boolean): Boolean = false

    fun useSherpaOnDevice(honorFamily: Boolean): Boolean = honorFamily
}
