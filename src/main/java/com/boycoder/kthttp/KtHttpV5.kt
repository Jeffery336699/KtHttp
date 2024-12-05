package com.boycoder.kthttp

import com.boycoder.kthttp.annotations.Field
import com.boycoder.kthttp.annotations.GET
import com.boycoder.kthttp.bean.RepoList
import com.google.gson.Gson
import com.google.gson.internal.`$Gson$Types`.getRawType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import kotlin.concurrent.thread

// https://trendings.herokuapp.com/repo?lang=java&since=weekly (已失效)
// https://api.github.com/search/users?q=Jeffery336699&sort=stars

interface ApiServiceV5 {
    @GET("/search/users")
    fun repos(
        @Field("q") q: String,
        @Field("sort") sort: String
    ): KtCall<RepoList>

    @GET("/search/users")
    fun reposSync(
        @Field("q") q: String,
        @Field("sort") sort: String
    ): RepoList


    @GET("/search/users")
    fun reposFlow(
        @Field("q") q: String,
        @Field("sort") sort: String
    ): Flow<RepoList>
}

object KtHttpV5 {

    private var okHttpClient: OkHttpClient = OkHttpClient()
    private var gson: Gson = Gson()
    var baseUrl = "https://api.github.com"

    fun <T : Any> create(service: Class<T>): T {
        return Proxy.newProxyInstance(
            service.classLoader,
            arrayOf<Class<*>>(service)
        ) { proxy, method, args ->
            val annotations = method.annotations
            for (annotation in annotations) {
                if (annotation is GET) {
                    val url = baseUrl + annotation.value
                    return@newProxyInstance invoke<T>(url, method, args!!)
                }
            }
            return@newProxyInstance null

        } as T
    }

    private fun <T : Any> invoke(path: String, method: Method, args: Array<Any>): Any? {
        if (method.parameterAnnotations.size != args.size) return null

        var url = path
        val parameterAnnotations = method.parameterAnnotations
        for (i in parameterAnnotations.indices) {
            for (parameterAnnotation in parameterAnnotations[i]) {
                if (parameterAnnotation is Field) {
                    val key = parameterAnnotation.value
                    val value = args[i].toString()
                    if (!url.contains("?")) {
                        url += "?$key=$value"
                    } else {
                        url += "&$key=$value"
                    }

                }
            }
        }

        val request = Request.Builder()
            .url(url)
            .build()

        val call = okHttpClient.newCall(request)

        return if (isKtCallReturn(method)) {
            val genericReturnType = getTypeArgument(method)
            KtCall<T>(call, gson, genericReturnType)
        } else {
            val response = okHttpClient.newCall(request).execute()

            val genericReturnType = method.genericReturnType
            val json = response.body?.string()
            gson.fromJson<Any?>(json, genericReturnType)
        }
    }

    private fun getTypeArgument(method: Method) =
        (method.genericReturnType as ParameterizedType).actualTypeArguments[0]

    private fun isKtCallReturn(method: Method) =
        getRawType(method.genericReturnType) == KtCall::class.java
}

fun <T : Any> KtCall<T>.asFlow1(): Flow<T> = callbackFlow {

    val job = launch {
        println("Coroutine start")
        delay(3000L)
        println("Coroutine end")
    }

    job.invokeOnCompletion {
        println("Coroutine completed $it")
    }

    val call = call(object : Callback<T> {
        override fun onSuccess(data: T) {
            trySendBlocking(data)
                .onSuccess { close() }
                .onFailure {
                    cancel(CancellationException("Send channel fail!", it))
                }
        }

        override fun onFail(throwable: Throwable) {
            cancel(CancellationException("Request fail!", throwable))
        }
    })

    awaitClose {
        call.cancel()
    }
}

fun <T : Any> KtCall<T>.asFlow(): Flow<T> = callbackFlow {
    val call = call(object : Callback<T> {
        override fun onSuccess(data: T) {
            trySendBlocking(data)
                .onSuccess { close() }
                .onFailure {
                    cancel(CancellationException("Send channel fail!", it))
                }
        }

        override fun onFail(throwable: Throwable) {
            cancel(CancellationException("Request fail!", throwable))
        }

    })

    awaitClose {
        call.cancel()
    }
}

fun <T : Any> KtCall<T>.asFlowTest(value: T): Flow<T> = callbackFlow {

    fun test(callback: Callback<T>) {
        thread(isDaemon = true) {
            Thread.sleep(2000L)
            callback.onSuccess(value)
        }
    }

    println("Start")
    test(object : Callback<T> {
        override fun onSuccess(data: T) {
            trySendBlocking(data)
                .onSuccess {
                    println("Send success")
//                    close()
                }
                .onFailure {
                    close()
                }
        }

        override fun onFail(throwable: Throwable) {
            close(throwable)
        }

    })

    awaitClose { }
}

fun main() = runBlocking {
    testAsFlow()
    logX("the end.")
}

private suspend fun testAsFlow() =
    KtHttpV5.create(ApiServiceV5::class.java)
        .repos(q = "Jeffery336699", sort = "stars")
        .asFlow1()
        .catch { println("Catch: $it") }
        .collect {
            println("size:${it.items.size}")
        }

/**
 * 控制台输出带协程信息的log
 */
fun logX(any: Any?) {
    println(
        """
================================
$any
Thread:${Thread.currentThread().name}
================================""".trimIndent()
    )
}
