package com.meridian.payments.intake.secrets;

import java.util.List;

public interface SecretProvider {

    List<String> secrets(String name);
}
