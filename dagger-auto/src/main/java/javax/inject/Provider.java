package javax.inject;

//NOTE: still needed for Dagger
@Deprecated
public interface Provider<T> {
    T get();
}
