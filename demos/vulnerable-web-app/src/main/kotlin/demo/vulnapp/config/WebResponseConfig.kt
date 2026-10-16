package demo.vulnapp.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Standard MVC responses use installed Jackson; profiler's serializer is an optional transitive peer. */
@Configuration
internal class WebResponseConfig : WebMvcConfigurer {
    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.removeAll { it is KotlinSerializationJsonHttpMessageConverter }
    }
}
