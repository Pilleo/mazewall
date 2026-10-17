package io.mazewall.consumer.koog;

/** Native test-only service; never added to the advertised Koog registry. */
public interface DeniedResourceService {
    String read(String path);
    String write(String path);
    String crash();
    String waitForever();
    String oversizedText();
    String oversizedWire();
}
