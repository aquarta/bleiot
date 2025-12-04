import androidx.compose.ui.input.key.type
import it.unisalento.bleiot.StructParserConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GenericStructParser(private val schema: StructParserConfig) {

    fun unpack(bytes: ByteArray): Map<String, Any> {        val buffer = ByteBuffer.wrap(bytes)

        // Set Endianness based on YAML config
        val order = if (schema.endianness.equals("LITTLE_ENDIAN", ignoreCase = true)) {
            ByteOrder.LITTLE_ENDIAN
        } else {
            ByteOrder.BIG_ENDIAN
        }
        buffer.order(order)

        val result = mutableMapOf<String, Any>()

        for (field in schema.fields) {
            // simple safety check to prevent buffer underflow crashes
            if (!buffer.hasRemaining()) break

            val value: Any = when (field.type) {
                "byte" -> buffer.get()
                // Kotlin trick for Unsigned Short: read short, convert to int, mask with 0xFFFF
                "ushort" -> buffer.short.toInt() and 0xFFFF
                "short" -> buffer.short.toInt()
                "int" -> buffer.int
                // Kotlin trick for Unsigned Int: read int, convert to long, mask with 0xFFFFFFFF
                "uint" -> buffer.int.toLong() and 0xFFFFFFFFL
                "long" -> buffer.long
                "float" -> buffer.float
                "double" -> buffer.double
                else -> throw IllegalArgumentException("Unsupported type: ${field.type} for field ${field.name}")
            }
            result[field.name] = value
        }
        return result
    }
}
