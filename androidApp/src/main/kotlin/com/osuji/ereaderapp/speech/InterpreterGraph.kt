package com.osuji.ereaderapp.speech

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

internal class InterpreterGraph(file: File) : Closeable {
    private val interpreter = Interpreter(file, Interpreter.Options().setNumThreads(2))
    private val inputStorage = HashMap<String, ByteBuffer>()

    data class Input(val shape: IntArray, val floats: FloatArray? = null, val ints: IntArray? = null)

    fun run(feeds: Map<String, Input>) {
        val inputs = arrayOfNulls<Any>(interpreter.inputTensorCount)
        var resized = false
        for (index in inputs.indices) {
            val tensor = interpreter.getInputTensor(index)
            val name = tensor.name().removePrefix("serving_default_").substringBefore(':')
            val input = checkNotNull(feeds[name]) { "Incompatible speech model input" }
            if (!tensor.shape().contentEquals(input.shape)) {
                interpreter.resizeInput(index, input.shape)
                resized = true
            }
            val elements = input.floats?.size ?: checkNotNull(input.ints).size
            require(input.shape.fold(1, Math::multiplyExact) == elements) { "Invalid speech input shape" }
            val byteCount = Math.multiplyExact(elements, 4)
            val storage = inputStorage[name]?.takeIf { it.capacity() >= byteCount }
                ?: ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).also { inputStorage[name] = it }
            storage.clear().limit(byteCount)
            val bytes = storage.slice().order(ByteOrder.nativeOrder())
            if (input.floats != null) bytes.asFloatBuffer().put(input.floats) else bytes.asIntBuffer().put(checkNotNull(input.ints))
            inputs[index] = bytes
        }
        if (resized) interpreter.allocateTensors()
        // Same-length sentences otherwise inherit fused LSTM state from the preceding sentence.
        interpreter.resetVariableTensors()
        interpreter.runForMultipleInputsOutputs(inputs, emptyMap<Int, Any>())
    }

    fun floats(suffix: String): FloatArray {
        val tensor = output(suffix)
        check(tensor.dataType() == DataType.FLOAT32) { "Incompatible speech model output" }
        val buffer = tensor.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    fun ints(suffix: String): IntArray {
        val tensor = output(suffix)
        check(tensor.dataType() == DataType.INT32) { "Incompatible speech duration output" }
        val buffer = tensor.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asIntBuffer()
        return IntArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun output(suffix: String) = (0 until interpreter.outputTensorCount)
        .map { interpreter.getOutputTensor(it) }.firstOrNull { it.name().endsWith(suffix) }
        ?: error("Incompatible speech model outputs")

    override fun close() { interpreter.close(); inputStorage.clear() }

    companion object {
        fun floats(shape: IntArray, values: FloatArray): Input {
            return Input(shape, floats = values)
        }

        fun ints(shape: IntArray, values: IntArray): Input {
            return Input(shape, ints = values)
        }
    }
}
