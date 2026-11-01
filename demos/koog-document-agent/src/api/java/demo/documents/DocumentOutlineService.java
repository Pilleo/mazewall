package demo.documents;

/** Bounded data only; no file, policy or operation selector crosses this interface. */
public interface DocumentOutlineService {
    String outline(String markdown);
}
