# The engine is reflection-free; only OkHttp's optional platform hooks need
# quieting.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
