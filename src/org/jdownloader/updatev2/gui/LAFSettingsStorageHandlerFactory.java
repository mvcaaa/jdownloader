package org.jdownloader.updatev2.gui;

import java.io.File;
import java.util.Locale;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.JsonKeyValueStorage;
import org.appwork.storage.config.JsonConfig;
import org.appwork.storage.config.StorageHandlerFactory;
import org.appwork.storage.config.handler.DefaultFactoryInterface;
import org.appwork.storage.config.handler.KeyHandler;
import org.appwork.storage.config.handler.StorageHandler;
import org.appwork.swing.synthetica.SyntheticaSettings;
import org.appwork.utils.Application;
import org.appwork.utils.logging2.extmanager.LoggerFactory;

public class LAFSettingsStorageHandlerFactory implements StorageHandlerFactory<LAFSettings> {

    private static boolean equals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    @Override
    public StorageHandler<LAFSettings> create(File path, Class<LAFSettings> configInterface) {
        final StorageHandler<LAFSettings> ret = new StorageHandler<LAFSettings>(path, configInterface) {

            @Override
            protected void preInit(File path, Class<LAFSettings> configInterfac) {
                setDefaultFactory(new DefaultFactoryInterface() {

                    @Override
                    public Object getDefaultValue(KeyHandler<?> handler, Object o) {
                        Object def = o;
                        try {
                            def = handler.getGetMethod().invoke(LAFOptions.getLookAndFeelExtension(), new Object[] {});
                        } catch (Throwable e) {
                            LoggerFactory.getDefaultLogger().log(e);
                        }
                        return def;

                    }
                });
            }
        };
        for (final KeyHandler<?> keyHandler : ret.getKeyHandler()) {
            keyHandler.setAllowWriteDefaultObjects(false);
        }
        // restore old storage
        try {
            final File oldLafSettingsFile = Application.getResource("cfg/org.appwork.swing.synthetica.SyntheticaSettings.json");
            if (oldLafSettingsFile.exists()) {
                final JsonKeyValueStorage prim = (JsonKeyValueStorage) JsonConfig.create(SyntheticaSettings.class)._getStorageHandler().getPrimitiveStorage();
                for (final String s : prim.getKeys()) {
                    final KeyHandler<Object> keyH = ret.getKeyHandler(s.toLowerCase(Locale.ENGLISH));
                    final Object oldValue = prim.get(s, null);
                    if (keyH != null && !equals(oldValue, keyH.getDefaultValue())) {
                        keyH.setValue(oldValue);
                    }
                }
                JsonConfig.create(SyntheticaSettings.class)._getStorageHandler().setSaveInShutdownHookEnabled(false);
                oldLafSettingsFile.delete();
            }
        } catch (Throwable e) {
            LoggerFactory.getDefaultLogger().log(e);
        }
        return ret;
    }

    @Override
    public StorageHandler<LAFSettings> create(String urlPath, Class<LAFSettings> configInterface) {
        throw new WTFException("Not Implemented");
    }

}
