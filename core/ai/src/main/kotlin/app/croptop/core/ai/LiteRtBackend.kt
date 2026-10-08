// LiteRT (TensorFlow Lite runtime) implementation of [InferenceBackend]. NPU = NNAPI, GPU = the LiteRT GPU
// delegate, CPU = XNNPACK; [DelegatePolicy] tries them in that order and falls back when a delegate cannot load the model.
package app.croptop.core.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate

class LiteRtBackend(private val cpuThreads: Int = defaultThreads()) : InferenceBackend {
    override fun open(model: File, delegate: Delegate): ModelSession {
        require(model.isFile) { "model file not found: ${model.name}" }
        val options = Interpreter.Options()
        var gpu: org.tensorflow.lite.Delegate? = null
        when (delegate) {
            Delegate.CPU -> options.setNumThreads(cpuThreads).setUseXNNPACK(true)

            Delegate.GPU -> {
                gpu = GpuDelegate()
                options.addDelegate(gpu)
            }

            Delegate.NPU -> options.setUseNNAPI(true).setNumThreads(cpuThreads)
        }
        return try {
            LiteRtSession(Interpreter(model, options), delegate, gpu)
        } catch (t: Throwable) {
            gpu?.close()
            throw t
        }
    }

    companion object {
        /** Big cores only pay off up to 4 threads for these models; leave one core for the UI. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}

private class LiteRtSession(
    private val interpreter: Interpreter,
    override val delegate: Delegate,
    private val gpu: org.tensorflow.lite.Delegate?,
) : ModelSession {
    private var input: ByteBuffer? = null
    private var output: ByteBuffer? = null
    private var shape: IntArray? = null

    @Synchronized
    override fun run(input: FloatArray, inputShape: IntArray): FloatArray {
        if (!inputShape.contentEquals(shape)) {
            interpreter.resizeInput(0, inputShape)
            interpreter.allocateTensors()
            shape = inputShape.copyOf()
            this.input = direct(interpreter.getInputTensor(0).numElements())
            this.output = direct(interpreter.getOutputTensor(0).numElements())
        }
        val inBuffer = requireNotNull(this.input)
        val outBuffer = requireNotNull(this.output)
        require(inBuffer.capacity() == input.size * Float.SIZE_BYTES) {
            "input has ${input.size} values, model expects ${inBuffer.capacity() / Float.SIZE_BYTES}"
        }
        inBuffer.rewind()
        inBuffer.asFloatBuffer().put(input)
        outBuffer.rewind()
        interpreter.run(inBuffer, outBuffer)
        outBuffer.rewind()
        val result = FloatArray(outBuffer.capacity() / Float.SIZE_BYTES)
        outBuffer.asFloatBuffer().get(result)
        return result
    }

    @Synchronized
    override fun runAll(inputs: List<FloatArray>, shapes: List<IntArray>): FloatArray {
        require(inputs.size == shapes.size && inputs.size == interpreter.inputTensorCount) {
            "model has ${interpreter.inputTensorCount} inputs, got ${inputs.size}"
        }
        var resized = false
        for (i in inputs.indices) {
            if (!interpreter.getInputTensor(i).shape().contentEquals(shapes[i])) {
                interpreter.resizeInput(i, shapes[i])
                resized = true
            }
        }
        if (resized) interpreter.allocateTensors()
        val buffers = Array<Any>(inputs.size) { i ->
            val expected = interpreter.getInputTensor(i).numElements()
            require(inputs[i].size == expected) { "input $i has ${inputs[i].size} values, model expects $expected" }
            direct(expected).also { it.asFloatBuffer().put(inputs[i]) }
        }
        val out = direct(interpreter.getOutputTensor(0).numElements())
        interpreter.runForMultipleInputsOutputs(buffers, mapOf(0 to out))
        out.rewind()
        return FloatArray(out.capacity() / Float.SIZE_BYTES).also { out.asFloatBuffer().get(it) }
    }

    @Synchronized
    override fun runOutputs(inputs: List<FloatArray>, shapes: List<IntArray>): List<FloatArray> {
        require(inputs.size == shapes.size && inputs.size == interpreter.inputTensorCount) {
            "model has ${interpreter.inputTensorCount} inputs, got ${inputs.size}"
        }
        val buffers = Array<Any>(inputs.size) { i ->
            val expected = interpreter.getInputTensor(i).numElements()
            require(inputs[i].size == expected) { "input $i has ${inputs[i].size} values, model expects $expected" }
            direct(expected).also { it.asFloatBuffer().put(inputs[i]) }
        }
        val outs = List(interpreter.outputTensorCount) { direct(interpreter.getOutputTensor(it).numElements()) }
        interpreter.runForMultipleInputsOutputs(buffers, outs.indices.associateWith { outs[it] as Any })
        return outs.map { out ->
            out.rewind()
            FloatArray(out.capacity() / Float.SIZE_BYTES).also { out.asFloatBuffer().get(it) }
        }
    }

    @Synchronized
    override fun close() {
        interpreter.close()
        gpu?.close()
    }

    private fun direct(elements: Int): ByteBuffer = ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
}
