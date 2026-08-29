# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# The @JavascriptInterface methods are reachable ONLY from the JS injected into the WebView, so R8
# sees no caller for them. AGP's proguard-android-optimize.txt already carries this exact rule (its
# 9.3.1 copy, lines 54-57), and the shipped release dex was checked to confirm saveBase64 /
# reportFailure / reportScrollTop survive un-renamed — but that is a default we do not control, and
# losing it would silently break downloads and pull-to-refresh in the release build only, with no
# crash and no log. State it here so it cannot go away underneath us.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile