package com.lyreo.learner.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.lyreo.learner.domain.LearnerProfile;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class LearnerAppearancePatchRequest {
    static final String WITHOUT_NUL = "[^\\x00]*";

    @Size(max = 80)
    @Pattern(regexp = WITHOUT_NUL, message = "Display name must not contain NUL")
    private String displayName;
    @Pattern(regexp = "CAT|DOG|FOX|RABBIT|PANDA|BEAR|RED_PANDA|KOALA|LION|PENGUIN|GIRAFFE|ORCA|TOUCAN"
        + "|FLOWER_(HEATHER|CLIMBING_LILY|CHRYSANTHEMUMS|ROSES_LILACS|IRISES|POTTED_PANSIES)"
        + "|PEEP_(CURLS|BEANIE|BUN|HEADSCARF|LONG_HAIR|QUIFF)"
        + "|COMIC_(MILO|LUNA|NOVA|KYO|MIKA|ARI)")
    private String avatarPreset;
    private boolean changeDisplayName;
    private boolean changeAvatar;

    @JsonSetter("displayName")
    public void setDisplayName(String name) {
        changeDisplayName = true;
        displayName = name == null || name.isBlank() ? null : name.strip();
    }

    @JsonSetter("avatarPreset")
    public void setAvatarPreset(String preset) {
        changeAvatar = true;
        avatarPreset = preset;
    }

    LearnerProfile.Appearance appearance() {
        return new LearnerProfile.Appearance(changeDisplayName, displayName, changeAvatar, avatarPreset);
    }
}
