package com.mrsmith.provider;

import com.mrsmith.config.AppConfig;

public interface ProviderFactory {

    Provider create(AppConfig config);
}
