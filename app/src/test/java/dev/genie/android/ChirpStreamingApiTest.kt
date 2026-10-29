package dev.genie.android

import com.google.protobuf.ByteString
import dev.genie.android.tts.proto.StreamingSynthesizeRequest
import dev.genie.android.tts.proto.StreamingSynthesizeResponse
import io.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ServerCalls
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChirpStreamingApiTest {
    @Test fun receivesPcmBeforeInputFinishesAndSendsConfigOnlyOnce() {
        val inputs = mutableListOf<StreamingSynthesizeRequest>()
        var authenticated = false
        var inputFinished = false
        val service = ServerServiceDefinition.builder("google.cloud.texttospeech.v1.TextToSpeech")
            .addMethod(ChirpStreamingApi.METHOD, ServerCalls.asyncBidiStreamingCall { responses ->
                object : StreamObserver<StreamingSynthesizeRequest> {
                    override fun onNext(value: StreamingSynthesizeRequest) {
                        inputs.add(value)
                        if (value.hasInput()) responses.onNext(StreamingSynthesizeResponse.newBuilder()
                            .setAudioContent(ByteString.copyFrom(byteArrayOf(1, 0, 2, 0))).build())
                    }
                    override fun onError(error: Throwable) = Unit
                    override fun onCompleted() { inputFinished = true; responses.onCompleted() }
                }
            }).build()
        val server = InProcessServerBuilder.forName("streaming-test").directExecutor()
            .intercept(object : ServerInterceptor {
                override fun <Q : Any?, S : Any?> interceptCall(call: ServerCall<Q, S>, headers: Metadata,
                    next: ServerCallHandler<Q, S>): ServerCall.Listener<Q> {
                    authenticated = headers.get(Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)) == "test-key"
                    return next.startCall(call, headers)
                }
            }).addService(service).build().start()
        val channel = InProcessChannelBuilder.forName("streaming-test").directExecutor().build()
        try {
            var audio: ByteArray? = null
            val received = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val stream = ChirpStreamingApi(channel).open("test-key", "en-US-Chirp3-HD-Aoede", 1.25f,
                object : ChirpStreamFactory.Listener {
                    override fun onAudio(pcm: ByteArray) { audio = pcm; received.countDown() }
                    override fun onComplete() { completed.countDown() }
                    override fun onError(code: Status.Code) { fail("Unexpected stream failure: $code") }
                })
            stream.send("Hello Genie ")
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertFalse(inputFinished)
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), audio)
            assertTrue(authenticated)
            assertEquals(2, inputs.size)
            val config = inputs[0].streamingConfig
            assertFalse(inputs[0].hasInput())
            assertEquals("en-US", config.voice.languageCode)
            assertEquals("en-US-Chirp3-HD-Aoede", config.voice.name)
            assertEquals(7, config.streamingAudioConfig.audioEncoding)
            assertEquals(24_000, config.streamingAudioConfig.sampleRateHertz)
            assertEquals(1.25, config.streamingAudioConfig.speakingRate, 0.00001)
            assertFalse(inputs[1].hasStreamingConfig())
            assertEquals("Hello Genie ", inputs[1].input.text)
            stream.send("more words ")
            stream.finish()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(inputFinished)
        } finally { channel.shutdownNow(); server.shutdownNow() }
    }

    @Test fun cancellationClosesTheRemoteStream() {
        val cancelled = CountDownLatch(1)
        val service = ServerServiceDefinition.builder("google.cloud.texttospeech.v1.TextToSpeech")
            .addMethod(ChirpStreamingApi.METHOD, ServerCalls.asyncBidiStreamingCall { responses ->
                (responses as ServerCallStreamObserver<StreamingSynthesizeResponse>).setOnCancelHandler { cancelled.countDown() }
                object : StreamObserver<StreamingSynthesizeRequest> {
                    override fun onNext(value: StreamingSynthesizeRequest) = Unit
                    override fun onError(error: Throwable) = Unit
                    override fun onCompleted() = Unit
                }
            }).build()
        val server = InProcessServerBuilder.forName("cancel-test").directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName("cancel-test").directExecutor().build()
        try {
            val stream = ChirpStreamingApi(channel).open("test", "en-GB-Chirp3-HD-Aoede", 1f,
                object : ChirpStreamFactory.Listener {
                    override fun onAudio(pcm: ByteArray) = Unit
                    override fun onComplete() = Unit
                    override fun onError(code: Status.Code) = Unit
                })
            stream.cancel()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        } finally { channel.shutdownNow(); server.shutdownNow() }
    }
}
