import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        // Diubah ke 2.4.0 agar sinkron dengan cloudstream.jar terbaru
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xskip-metadata-version-check" // Ditambahkan di sini agar aman
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

import javax.net.ssl.*
import java.security.cert.X509Certificate

// Bypass SSL certificate validation for build script network requests
try {
    def trustAllCerts = [
        new X509Certificate() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void verify(PublicKey key) {}
            public void verify(PublicKey key, String sigProvider) {}
            public String toString() { return ""; }
            public boolean hasExpired() { return false; }
            public Date getNotBefore() { return new Date(); }
            public Date getNotAfter() { return new Date(Long.MAX_VALUE); }
            public byte[] getTBSCertificate() { return new byte[0]; }
            public byte[] getSignature() { return new byte[0]; }
            public String getSigAlgName() { return ""; }
            public String getSigAlgOID() { return ""; }
            public byte[] getSigAlgParams() { return new byte[0]; }
            public boolean[] getIssuerUniqueID() { return null; }
            public boolean[] getSubjectUniqueID() { return null; }
            public boolean[] getKeyUsage() { return null; }
            public int getVersion() { return 1; }
            public java.math.BigInteger getSerialNumber() { return java.math.BigInteger.ONE; }
            public javax.security.auth.x500.X500Principal getIssuerX500Principal() { return new javax.security.auth.x500.X500Principal("CN=Dummy"); }
            public javax.security.auth.x500.X500Principal getSubjectX500Principal() { return new javax.security.auth.x500.X500Principal("CN=Dummy"); }
        }
    ] as TrustManager[]

    SSLContext sc = SSLContext.getInstance("SSL")
    sc.init(null, trustAllCerts, new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
    HttpsURLConnection.setDefaultHostnameVerifier({ _, _ -> true } as HostnameVerifier)
} catch (Exception e) {
    e.printStackTrace()
}
