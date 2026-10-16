package demo.boot;

/** Only this synchronous, registered singleton entry is supported. */
public interface Reports {
    String generate(String mode) throws Exception;
}
