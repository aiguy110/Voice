package voice.features.murmur

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A book's state as Smart AudioBook Player keeps it in `position.sabp.dat` inside the book folder: a Java-serialized
 * `ak.alizandro.smartaudiobookplayer.BookDataBackup`.
 */
data class SabpState(
  val finished: Boolean,
  val started: Boolean,
  /** The current file, relative to the book folder. */
  val fileName: String?,
  val positionInFileMs: Long,
  val playbackSpeed: Float,
  /** Epoch millis, or 0 if never played. */
  val lastPlayedAtMs: Long,
) {

  companion object {
    const val FILE_NAME = "position.sabp.dat"

    /** Returns null if [input] isn't a Smart AudioBook Player backup. */
    fun parse(input: InputStream): SabpState? {
      val root = try {
        JavaSerialReader(input).read()
      } catch (_: IOException) {
        return null
      }
      if (root !is JavaSerialReader.Object || root.className != "ak.alizandro.smartaudiobookplayer.BookDataBackup") return null
      val fields = root.fields
      val state = (fields["mBookState"] as? JavaSerialReader.Enum)?.constant
      return SabpState(
        finished = state == "Finished",
        started = state == "Started",
        fileName = fields["mFileName"] as? String,
        positionInFileMs = ((fields["mFilePosition"] as? Int) ?: 0) * 1000L,
        playbackSpeed = (fields["mPlaybackSpeed"] as? Float)?.takeIf { it > 0F } ?: 1F,
        lastPlayedAtMs = (fields["mLastPlaybackTime"] as? Long) ?: 0L,
      )
    }
  }
}

/**
 * Reads a Java Object Serialization stream into plain maps without needing the classes. Handles what serializable
 * POJOs produce; custom `writeObject` data is skipped, and externalizable classes are rejected.
 */
internal class JavaSerialReader(input: InputStream) {

  class Object(
    val className: String,
    val fields: MutableMap<String, Any?>,
  )
  class Enum(
    val className: String,
    val constant: String,
  )

  private class ClassDesc(
    val name: String,
    val flags: Int,
    val fields: List<Pair<Char, String>>,
    val superClass: ClassDesc?,
  )

  private object EndBlock

  private val data = DataInputStream(input)
  private val handles = mutableListOf<Any?>()

  fun read(): Any? {
    if (data.readUnsignedShort() != 0xACED || data.readUnsignedShort() != 5) throw IOException("not a serialization stream")
    return readContent().also { if (it === EndBlock) throw IOException("unexpected end block") }
  }

  private fun readContent(): Any? = when (val tc = data.readUnsignedByte()) {
    TC_NULL -> null
    TC_REFERENCE -> handles.getOrNull(data.readInt() - BASE_HANDLE) ?: throw IOException("bad handle")
    TC_STRING -> data.readUTF().also { handles += it }
    TC_CLASSDESC -> readClassDescBody()
    TC_OBJECT -> readObject()
    TC_ENUM -> readEnum()
    TC_ARRAY -> readArray()
    TC_BLOCKDATA -> skip(data.readUnsignedByte().toLong())
    TC_BLOCKDATALONG -> skip(data.readInt().toLong())
    TC_ENDBLOCKDATA -> EndBlock
    else -> throw IOException("unsupported type code 0x${tc.toString(16)}")
  }

  /** Skips block data (custom `writeObject` output), which carries nothing we read. */
  private fun skip(count: Long): Nothing? {
    if (data.skip(count) != count) throw IOException("truncated block")
    return null
  }

  private fun readClassDesc(): ClassDesc? = when (val desc = readContent()) {
    null, is ClassDesc -> desc
    else -> throw IOException("expected class descriptor")
  }

  private fun readClassDescBody(): ClassDesc {
    val name = data.readUTF()
    data.readLong() // serialVersionUID
    val handle = handles.size
    handles += null
    val flags = data.readUnsignedByte()
    val fields = List(data.readUnsignedShort()) {
      val type = data.readUnsignedByte().toChar()
      val fieldName = data.readUTF()
      if (type == 'L' || type == '[') {
        val _ = readContent() // the field's class name
      }
      type to fieldName
    }
    skipUntilEndBlock() // class annotations
    return ClassDesc(name, flags, fields, readClassDesc()).also { handles[handle] = it }
  }

  private fun readObject(): Object {
    val desc = readClassDesc() ?: throw IOException("object without class")
    val obj = Object(desc.name, mutableMapOf())
    handles += obj
    if (desc.flags and SC_EXTERNALIZABLE != 0) throw IOException("externalizable ${desc.name}")
    generateSequence(desc) { it.superClass }.toList().asReversed().forEach { cls ->
      cls.fields.forEach { (type, name) -> obj.fields[name] = readValue(type) }
      if (cls.flags and SC_WRITE_METHOD != 0) skipUntilEndBlock()
    }
    return obj
  }

  private fun readEnum(): Enum {
    val desc = readClassDesc() ?: throw IOException("enum without class")
    val handle = handles.size
    handles += null
    val constant = readContent() as? String ?: throw IOException("enum constant is not a string")
    return Enum(desc.name, constant).also { handles[handle] = it }
  }

  private fun readArray(): List<Any?> {
    val desc = readClassDesc() ?: throw IOException("array without class")
    val list = mutableListOf<Any?>()
    handles.add(list)
    val type = desc.name.getOrNull(1) ?: throw IOException("bad array class ${desc.name}")
    repeat(data.readInt()) { list += readValue(type) }
    return list
  }

  private fun readValue(type: Char): Any? = when (type) {
    'B' -> data.readByte()
    'C' -> data.readChar()
    'D' -> data.readDouble()
    'F' -> data.readFloat()
    'I' -> data.readInt()
    'J' -> data.readLong()
    'S' -> data.readShort()
    'Z' -> data.readBoolean()
    'L', '[' -> readContent()
    else -> throw IOException("bad field type $type")
  }

  private fun skipUntilEndBlock() {
    do {
      val content = readContent()
    } while (content !== EndBlock)
  }

  private companion object {
    const val BASE_HANDLE = 0x7E0000
    const val SC_WRITE_METHOD = 0x01
    const val SC_EXTERNALIZABLE = 0x04
    const val TC_NULL = 0x70
    const val TC_REFERENCE = 0x71
    const val TC_CLASSDESC = 0x72
    const val TC_OBJECT = 0x73
    const val TC_STRING = 0x74
    const val TC_ARRAY = 0x75
    const val TC_BLOCKDATA = 0x77
    const val TC_ENDBLOCKDATA = 0x78
    const val TC_BLOCKDATALONG = 0x7A
    const val TC_ENUM = 0x7E
  }
}
