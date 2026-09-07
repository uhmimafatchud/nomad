package nomad.client.mixin.accessor;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Nomad swap the active session on the live {@link Minecraft} instance without a restart. */
@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
    @Mutable
    @Accessor("user")
    void nomad$setSession(User session);

    @Mutable
    @Accessor("userApiService")
    void nomad$setUserApiService(UserApiService apiService);

    @Mutable
    @Accessor("playerSocialManager")
    void nomad$setSocialInteractionsManager(PlayerSocialManager socialInteractionsManager);

    @Mutable
    @Accessor("profileKeyPairManager")
    void nomad$setProfileKeys(ProfileKeyPairManager keys);

    @Mutable
    @Accessor("reportingContext")
    void nomad$setReportingContext(ReportingContext reportingContext);
}
