package tw.kurocore.kurogram.features.impl;

import tw.kurocore.kurogram.feature.FeaturesProvider;
import tw.kurocore.kurogram.feature.KuroFeatures;

/**
 * Entry point of this module. The core finds it by name (Class.forName) at app start, so the class name,
 * its package and the public no-argument constructor must not change.
 */
public final class FeaturesBootstrap implements FeaturesProvider {

    public FeaturesBootstrap() {
    }

    @Override
    public int apiVersion() {
        return KuroFeatures.API_VERSION;
    }

    @Override
    public void register(Registrar registrar) {
        registrar.themes(new ThemeStoreImpl());
        registrar.specialContacts(new SpecialContactsImpl());
        registrar.contactChanges(new ContactChangesImpl());
    }
}
