package io.reist.sklad;

/**
 * Created by reist on 12.04.17.
 */

public interface CacheStatusHolder {

    boolean isCached(String id);

    void put(String id, boolean cached);

}
