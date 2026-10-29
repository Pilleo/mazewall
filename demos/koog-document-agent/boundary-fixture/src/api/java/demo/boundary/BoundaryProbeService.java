package demo.boundary;

/** Fault controls for tests only; absent from the normal application and advertised registry. */
public interface BoundaryProbeService {
    String read(String path);
    String crash();
}
