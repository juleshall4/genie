package dev.genie.android

import dev.genie.android.tts.proto.StreamingAudioConfig
import dev.genie.android.tts.proto.StreamingSynthesisInput
import dev.genie.android.tts.proto.StreamingSynthesizeConfig
import dev.genie.android.tts.proto.StreamingSynthesizeRequest
import dev.genie.android.tts.proto.StreamingSynthesizeResponse
import dev.genie.android.tts.proto.VoiceSelectionParams
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.protobuf.lite.ProtoLiteUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import java.util.concurrent.TimeUnit

interface ChirpStream {
    fun send(text: String)
    fun finish()
    fun cancel()
}

fun interface ChirpStreamFactory {
    fun open(key: String, voice: String, rate: Float, listener: Listener): ChirpStream

    interface Listener {
        fun onAudio(pcm: ByteArray)
        fun onComplete()
        fun onError(code: Status.Code)
    }
}

/** Android-compatible gRPC transport; one shared TLS connection, one stream per answer. */
class ChirpStreamingApi(private val channel: Channel = sharedChannel) : ChirpStreamFactory {
    override fun open(key: String, voice: String, rate: Float, listener: ChirpStreamFactory.Listener): ChirpStream {
        require(rate.isFinite() && rate in 0.25f..2f)
        val headers = Metadata().apply { put(API_KEY, key) }
        val authenticated = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
        val call = authenticated.newCall(METHOD, CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.MINUTES))
        val requests = ClientCalls.asyncBidiStreamingCall(call, object : StreamObserver<StreamingSynthesizeResponse> {
            override fun onNext(value: StreamingSynthesizeResponse) {
                val bytes = value.audioContent.toByteArray()
                if (bytes.isNotEmpty()) listener.onAudio(bytes)
            }
            override fun onCompleted() = listener.onComplete()
            override fun onError(error: Throwable) = listener.onError(Status.fromThrowable(error).code)
        })
        requests.onNext(StreamingSynthesizeRequest.newBuilder().setStreamingConfig(
            StreamingSynthesizeConfig.newBuilder().setVoice(
                VoiceSelectionParams.newBuilder().setLanguageCode(voice.substringBefore("-Chirp3")).setName(voice)
            ).setStreamingAudioConfig(StreamingAudioConfig.newBuilder()
                .setAudioEncoding(7).setSampleRateHertz(24_000).setSpeakingRate(rate.toDouble()))
        ).build())
        return object : ChirpStream {
            override fun send(text: String) {
                require(text.toByteArray(Charsets.UTF_8).size <= 5000)
                requests.onNext(StreamingSynthesizeRequest.newBuilder().setInput(
                    StreamingSynthesisInput.newBuilder().setText(text)
                ).build())
            }
            override fun finish() = requests.onCompleted()
            override fun cancel() = call.cancel("Speech stopped", null)
        }
    }

    companion object {
        private val API_KEY = Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)
        val METHOD: MethodDescriptor<StreamingSynthesizeRequest, StreamingSynthesizeResponse> =
            MethodDescriptor.newBuilder<StreamingSynthesizeRequest, StreamingSynthesizeResponse>()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName("google.cloud.texttospeech.v1.TextToSpeech/StreamingSynthesize")
                .setRequestMarshaller(ProtoLiteUtils.marshaller(StreamingSynthesizeRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoLiteUtils.marshaller(StreamingSynthesizeResponse.getDefaultInstance()))
                .build()
        private val sharedChannel by lazy {
            OkHttpChannelBuilder.forAddress("texttospeech.googleapis.com", 443).useTransportSecurity().build()
        }
    }
}
