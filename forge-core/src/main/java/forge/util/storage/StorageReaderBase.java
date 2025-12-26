package forge.util.storage;

import com.google.common.collect.ImmutableList;
import forge.util.IItemReader;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

public abstract class StorageReaderBase<T> implements IItemReader<T> {
    // Use IKeySelector instead of Function (not available on iOS runtime)
    protected final IKeySelector<? super T> keySelector;
    public StorageReaderBase(final IKeySelector<? super T> keySelector0) {
        keySelector = keySelector0;
    }

    protected Map<String, T> createMap() {
        return new TreeMap<>();
    }

    @Override
    public Iterable<File> getSubFolders() {
        // TODO Auto-generated method stub
        return ImmutableList.of();
    }

    @Override
    public IItemReader<T> getReaderForFolder(File subfolder) {
        throw new UnsupportedOperationException("This reader is not supposed to have nested folders");
    }
}
