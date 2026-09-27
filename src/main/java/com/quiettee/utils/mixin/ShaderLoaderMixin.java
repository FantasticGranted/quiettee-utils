package com.quiettee.utils.mixin;

import com.quiettee.utils.render.QuietteePipelines;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public abstract class ShaderLoaderMixin {
    @Inject(method = "apply(Lnet/minecraft/client/gl/ShaderManager$Definitions;Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/profiler/Profiler;)V", at = @At("TAIL"))
    private void quiettee$reloadPipelines(CallbackInfo info) {
        QuietteePipelines.precompile();
    }
}
