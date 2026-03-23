package com.isle.evrima.bot.security;

public enum StaffTier {
    PLAYER,
    MODERATOR,
    ADMIN,
    HEAD_ADMIN;

    public boolean isAtLeast(StaffTier other) {
        return this.ordinal() >= other.ordinal();
    }
}
