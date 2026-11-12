package com.github.catvod.utils

import android.net.Uri
import android.os.Environment
import com.github.catvod.Init
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipFile

/** 文件与目录工具，jar 内爬虫通过 com.github.catvod.utils.Path 访问文件系统 */
object Path {

    private const val TAG = "Path"

    private fun check(file: File): File {
        if (!file.exists()) file.mkdirs()
        return file
    }

    @JvmStatic
    fun exists(path: String): Boolean = File(path.replace("file://", "")).exists()

    @JvmStatic
    fun root(): File = Environment.getExternalStorageDirectory()

    @JvmStatic
    fun cache(): File = Init.context()!!.cacheDir

    @JvmStatic
    fun files(): File = Init.context()!!.filesDir

    @JvmStatic
    fun externalFiles(): File? = Init.context()!!.getExternalFilesDir("")

    @JvmStatic
    fun externalCache(): File? = Init.context()!!.externalCacheDir

    @JvmStatic
    fun rootPath(): String = root().absolutePath

    @JvmStatic
    fun so(): File = check(File(files().absolutePath + File.separator + "so"))

    @JvmStatic
    fun js(): File = check(File(cache().absolutePath + File.separator + "js"))

    @JvmStatic
    fun py(): File = check(File(cache().absolutePath + File.separator + "py"))

    @JvmStatic
    fun jar(): File = check(File(cache().absolutePath + File.separator + "jar"))

    @JvmStatic
    fun doh(): File = check(File(cache().absolutePath + File.separator + "doh"))

    @JvmStatic
    fun exo(): File = check(File(cache().absolutePath + File.separator + "exo"))

    @JvmStatic
    fun jpa(): File = check(File(cache().absolutePath + File.separator + "jpa"))

    @JvmStatic
    fun thunder(): File = check(File(cache().absolutePath + File.separator + "thunder"))

    @JvmStatic
    fun root(name: String): File = File(root(), name)

    @JvmStatic
    fun root(child: String, name: String): File = File(check(File(root(), child)), name)

    @JvmStatic
    fun cache(name: String): File = File(cache(), name)

    @JvmStatic
    fun files(name: String): File = File(files(), name)

    @JvmStatic
    fun so(name: String): File {
        if (name.startsWith("http")) {
            return File(so(), Uri.parse(name).lastPathSegment ?: "unknown")
        }
        return File(if ("mitv" == name) cache() else so(), "lib$name.so")
    }

    @JvmStatic
    fun js(name: String): File = File(js(), name)

    @JvmStatic
    fun jar(name: String): File = File(jar(), Util.md5(name) + ".jar")

    @JvmStatic
    fun thunder(name: String): File = check(File(thunder(), name))

    @JvmStatic
    fun local(path: String): File {
        val file1 = File(path.replace("file:/", ""))
        val file2 = File(path.replace("file:/", rootPath()))
        return when {
            file2.exists() -> file2
            file1.exists() -> file1
            else -> File(path)
        }
    }

    @JvmStatic
    fun asset(fileName: String): String = try {
        read(Init.context()!!.assets.open(fileName))
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun read(file: File): String = try {
        read(FileInputStream(file))
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun read(path: String): String = try {
        read(FileInputStream(local(path)))
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun read(inputStream: InputStream): String = try {
        val data = ByteArray(inputStream.available())
        inputStream.read(data)
        inputStream.close()
        String(data, StandardCharsets.UTF_8)
    } catch (_: IOException) {
        ""
    }

    @JvmStatic
    fun write(file: File, data: ByteArray): File = try {
        FileOutputStream(file).use { fos ->
            fos.write(data)
            fos.flush()
        }
        chmod(file)
        file
    } catch (_: Exception) {
        file
    }

    @JvmStatic
    fun move(inFile: File, outFile: File) {
        copy(inFile, outFile)
        clear(inFile)
    }

    @JvmStatic
    fun copy(inFile: File, outFile: File) {
        try {
            FileInputStream(inFile).use { input -> FileOutputStream(outFile).use { copy(input, it) } }
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun copy(input: InputStream, outFile: File) {
        try {
            FileOutputStream(outFile).use { copy(input, it) }
        } catch (_: Exception) {
        }
    }

    @Throws(IOException::class)
    fun copy(inputStream: InputStream, outputStream: OutputStream) {
        val buffer = ByteArray(8192)
        var amountRead: Int
        while (inputStream.read(buffer).also { amountRead = it } != -1) {
            outputStream.write(buffer, 0, amountRead)
        }
    }

    @JvmStatic
    fun list(dir: File): List<File> {
        val files = dir.listFiles()
        return files?.toList() ?: Collections.emptyList()
    }

    @JvmStatic
    fun clear(dir: File?) {
        if (dir == null) return
        if (dir.isDirectory) for (file in list(dir)) clear(file)
        dir.delete()
    }

    @JvmStatic
    fun unzip(target: File, path: File) {
        try {
            ZipFile(target.absolutePath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val out = File(path, entry.name)
                    if (entry.isDirectory) out.mkdirs()
                    else zip.getInputStream(entry).use { copy(it, out) }
                }
            }
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun chmod(file: File): File = try {
        val process = Runtime.getRuntime().exec("chmod 777 " + file.absolutePath)
        process.waitFor()
        file
    } catch (_: Exception) {
        file
    }
}
