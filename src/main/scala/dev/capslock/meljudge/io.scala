package dev.capslock.meljudge

import java.io.InputStream
import java.nio.charset.StandardCharsets

private[meljudge] object Io:
  def readAll(in: InputStream): String =
    val buf = new Array[Byte](8192)
    val out = new java.io.ByteArrayOutputStream()
    var n = in.read(buf)
    while n != -1 do
      out.write(buf, 0, n)
      n = in.read(buf)
    out.toString(StandardCharsets.UTF_8)
