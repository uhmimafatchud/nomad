package nomad.client.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import nomad.client.gui.NomadHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MixinJoinMultiplayerScreen {
    @Inject(method = "init", at = @At("TAIL"))
    private void nomad$addHudButtons(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        NomadHud.addButtons(self, self.width, self.height);
    }
}
