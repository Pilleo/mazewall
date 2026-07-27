package io.mazewall.orchestrator

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse<String>
}

class RealHttpTransport(private val client: HttpClient) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse<String> {
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
