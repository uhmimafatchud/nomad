package nomad.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import nomad.client.gui.NomadScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Fabric-API keybinding module for this MC version yet, so Nomad polls GLFW directly:
 *  press N (edge-triggered) with no screen open to bring up the switcher. Skipped while a text
 *  box is focused so it doesn't hijack the letter while typing a server address/name. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    private boolean nomad$wasDown;

    @Inject(method = "tick", at = @At("TAIL"))
    private void nomad$pollOpenKey(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        long window = mc.getWindow().handle();
        boolean down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_N) == GLFW.GLFW_PRESS;
        boolean typing = mc.screen != null && mc.screen.getFocused() instanceof EditBox;
        if (down && !nomad$wasDown && !typing && !(mc.screen instanceof NomadScreen)) {
            mc.setScreen(new NomadScreen(mc.screen));
        }
        nomad$wasDown = down;
    }
}
