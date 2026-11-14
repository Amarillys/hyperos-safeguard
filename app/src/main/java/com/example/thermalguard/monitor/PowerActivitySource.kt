package com.example.thermalguard.monitor

import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.ByteArrayOutputStream

/** A public diagnostic API with explicitly ADB-granted DUMP; nothing is persisted from the dump. */
class PowerActivitySource {
    suspend fun readLastActivityUptimeMs(): Long = coroutineScope {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val reader = async(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 256 * 1024) throw IOException("电源诊断输出超出预期")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            val success = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                    Debug.dumpService(Context.POWER_SERVICE, it.fd, arrayOf("--proto"))
                }
            }
            val data = reader.await()
            if (!success) throw IOException("无法读取电源诊断，请检查 ADB 授权")
            PowerActivityParser.lastActivityUptimeMs(data)
                ?: throw IOException("无法识别系统最近交互时间，已暂停调暗")
        } finally {
            pipe.forEach { try { it.close() } catch (_: IOException) { } }
        }
    }
}
