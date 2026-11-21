package buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Stages the hash-pinned Node runtime pack as jniLibs and an assets/runtime/cacert.pem.
 *
 * This is a real task class rather than a doLast lambda so Gradle's configuration cache can
 * serialize it. Inputs: native-manifest.json. Output: the generated bundledNode directory.
 */
abstract class PrepareBundledNodeTask : DefaultTask() {
  @get:InputFile
  abstract val manifest: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun prepare() {
    val manifestFile = manifest.get().asFile
    @Suppress("UNCHECKED_CAST")
    val packs = (JsonSlurper().parse(manifestFile) as Map<String, Any?>)["packs"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val node = packs["node"] as Map<String, Any?>
    val url = node["url"] as String
    val sha = (node["sha256"] as String).lowercase()

    val root = outputDir.get().asFile
    val jni = File(root, "jniLibs/arm64-v8a").apply { mkdirs() }
    val assets = File(root, "assets/runtime").apply { mkdirs() }
    val zip = File(root, "node-pack.zip")

    if (!zip.isFile || sha256(zip) != sha) {
      root.mkdirs()
      logger.lifecycle("Downloading the pinned Node runtime pack...")
      URI(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
      val got = sha256(zip)
      check(got == sha) { "Node pack checksum mismatch: expected $sha, got $got" }
    }

    var nodeWritten = false
    var caWritten = false
    ZipInputStream(zip.inputStream().buffered()).use { stream ->
      while (true) {
        val entry = stream.nextEntry ?: break
        val name = entry.name.replace('\\', '/')
        when {
          name == "bin/node" -> {
            File(jni, "libnode.so").outputStream().use { stream.copyTo(it) }
            nodeWritten = true
          }
          name.startsWith("lib/") && name.endsWith(".so") -> {
            val out = File(jni, name.removePrefix("lib/"))
            out.parentFile?.mkdirs()
            out.outputStream().use { stream.copyTo(it) }
          }
          name == "cacert.pem" -> {
            File(assets, "cacert.pem").outputStream().use { stream.copyTo(it) }
            caWritten = true
          }
        }
      }
    }
    check(nodeWritten) { "The Node pack has no bin/node" }
    check(caWritten) { "The Node pack has no cacert.pem" }
  }

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(256 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}
