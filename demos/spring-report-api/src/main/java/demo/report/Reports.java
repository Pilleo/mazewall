package demo.report;

public interface Reports {
    String generate(String requestId, String templateName) throws Exception;
}
