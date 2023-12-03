package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.chunk.AreaUploadManager;
import net.vulkanmod.render.chunk.TerrainShaderManager;
import net.vulkanmod.render.profiling.Profiler2;
import net.vulkanmod.vulkan.framebuffer.*;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.passes.DefaultMainPass;
import net.vulkanmod.vulkan.passes.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.mojang.blaze3d.platform.GlConst.GL_COLOR_BUFFER_BIT;
import static com.mojang.blaze3d.platform.GlConst.GL_DEPTH_BUFFER_BIT;
import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.Vulkan.getSwapChain;
import static net.vulkanmod.vulkan.queue.Queue.GraphicsQueue;
import static net.vulkanmod.vulkan.queue.Queue.PresentQueue;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class Renderer {
    private static Renderer INSTANCE;

    private static VkDevice device;

    private static boolean swapChainUpdate = false;
    public static boolean skipRendering = false;
    public static void initRenderer() {
        INSTANCE = new Renderer();
        INSTANCE.init();
    }

    public static Renderer getInstance() { return INSTANCE; }

    public static Drawer getDrawer() { return INSTANCE.drawer; }

    public static int getCurrentFrame() { return currentFrame; }

    public static int getCurrentImage() { return imageIndex; }

    private final Set<Pipeline> usedPipelines = new ObjectOpenHashSet<>();

    private Drawer drawer;

    private int framesNum;
    private int imagesNum;
    private List<VkCommandBuffer> commandBuffers;
    private ArrayList<Long> imageAvailableSemaphores;
    private ArrayList<Long> renderFinishedSemaphores;
    private ArrayList<Long> inFlightFences;

//    private Framebuffer boundFramebuffer;
//    private RenderPass boundRenderPass;

    private static int currentFrame = 0;
    private static int imageIndex;
    private VkCommandBuffer currentCmdBuffer;

    MainPass mainPass = DefaultMainPass.PASS;

    private final List<Runnable> onResizeCallbacks = new ObjectArrayList<>();
    public static final Framebuffer2 tstFRAMEBUFFER_2 = new Framebuffer2(getSwapChain().getWidth(), getSwapChain().getHeight());
    public Renderer() {
        device = Vulkan.getDevice();
        framesNum = getSwapChain().getFramesNum();
        imagesNum = getSwapChain().getImagesNum();


    }

    private void init() {
        MemoryManager.createInstance(Renderer.getFramesNum());
        Vulkan.createStagingBuffers();

        drawer = new Drawer();
        drawer.createResources(framesNum);

        Uniforms.setupDefaultUniforms();
        TerrainShaderManager.init();
        AreaUploadManager.createInstance();

        allocateCommandBuffers();
        createSyncObjects();

        AreaUploadManager.INSTANCE.init();
    }

    private void allocateCommandBuffers() {
        if(commandBuffers != null) {
            commandBuffers.forEach(commandBuffer -> vkFreeCommandBuffers(device, Vulkan.getCommandPool(), commandBuffer));
        }

        commandBuffers = new ArrayList<>(framesNum);

        try(MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(framesNum);

            PointerBuffer pCommandBuffers = stack.mallocPointer(framesNum);

            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers");
            }

            for (int i = 0; i < framesNum; i++) {
                commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private void createSyncObjects() {
        imageAvailableSemaphores = new ArrayList<>(framesNum);
        renderFinishedSemaphores = new ArrayList<>(framesNum);
        inFlightFences = new ArrayList<>(framesNum);

        try(MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
            LongBuffer pRenderFinishedSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for(int i = 0;i < framesNum; i++) {

                if(vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                        || vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS
                        || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {

                    throw new RuntimeException("Failed to create synchronization objects for the frame " + i);
                }

                imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
                renderFinishedSemaphores.add(pRenderFinishedSemaphore.get(0));
                inFlightFences.add(pFence.get(0));

            }

        }
    }

    public void beginFrame() {

        if(VRenderSystem.renderPassUpdate) this.updateFrameBuffer();

        Profiler2 p = Profiler2.getMainProfiler();
        p.pop();
        p.push("Frame_fence");

        if(swapChainUpdate) {
            recreateSwapChain();
            swapChainUpdate = false;

            if(getSwapChain().getWidth() == 0 && getSwapChain().getHeight() == 0) {
                skipRendering = true;
                Minecraft.getInstance().noRender = true;
            }
            else {
                skipRendering = false;
                Minecraft.getInstance().noRender = false;
            }
        }


        if(skipRendering)
            return;

        vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);

        p.pop();
        p.start();
        p.push("Begin_rendering");

//        AreaUploadManager.INSTANCE.updateFrame();

        MemoryManager.getInstance().initFrame(currentFrame);
        drawer.setCurrentFrame(currentFrame);

        //Moved before texture updates
//        this.vertexBuffers[currentFrame].reset();
//        this.uniformBuffers.reset();
//        Vulkan.getStagingBuffer(currentFrame).reset();

        resetDescriptors();

        currentCmdBuffer = commandBuffers.get(currentFrame);
        vkResetCommandBuffer(currentCmdBuffer, 0);

        try(MemoryStack stack = stackPush()) {

            IntBuffer pImageIndex = stack.mallocInt(1);

            int vkResult = vkAcquireNextImageKHR(device, Vulkan.getSwapChain().getId(), VUtil.UINT64_MAX,
                    imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex);

            if(vkResult == VK_SUBOPTIMAL_KHR ) {
                swapChainUpdate = true;
            }
            else if(vkResult == VK_ERROR_OUT_OF_DATE_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                return;
            } else if(vkResult != VK_SUCCESS) {
                throw new RuntimeException("Cannot get image: " + vkResult);
            }

            imageIndex = pImageIndex.get(0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkCommandBuffer commandBuffer = currentCmdBuffer;

            int err = vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to begin recording command buffer:" + err);
            }

            mainPass.begin(commandBuffer, stack);

            vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);
        }

        p.pop();
    }

    public void endFrame() {
        if(skipRendering)
            return;

        Profiler2 p = Profiler2.getMainProfiler();
        p.push("End_rendering");

        mainPass.end(currentCmdBuffer);

        submitFrame();

        p.pop();
    }

    public void endRenderPass() {
        endRenderPass(currentCmdBuffer);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        if(!DYNAMIC_RENDERING)
                vkCmdEndRenderPass(commandBuffer);
        else
            KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);

//        this.boundRenderPass = null;
    }

    //TODO
    public void beginRendering() {

    }

    public void resetBuffers() {
        Profiler2 p = Profiler2.getMainProfiler();
        p.push("Frame_ops");

        drawer.resetBuffers(currentFrame);

        AreaUploadManager.INSTANCE.updateFrame();
        Vulkan.getStagingBuffer().reset();
    }

    public void addUsedPipeline(Pipeline pipeline) {
        usedPipelines.add(pipeline);
    }

    public void removeUsedPipeline(Pipeline pipeline) { usedPipelines.remove(pipeline); }

    private void resetDescriptors() {
        for(Pipeline pipeline : usedPipelines) {
            pipeline.resetDescriptorPool(currentFrame);
        }

        usedPipelines.clear();
    }

    private void submitFrame() {
        if(swapChainUpdate)
            return;

        try(MemoryStack stack = stackPush()) {

            int vkResult;

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stackGet().longs(imageAvailableSemaphores.get(currentFrame)));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));

            submitInfo.pSignalSemaphores(stackGet().longs(renderFinishedSemaphores.get(currentFrame)));

            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            vkResetFences(device, stackGet().longs(inFlightFences.get(currentFrame)));

            Synchronization.INSTANCE.waitFences();

            if((vkResult = vkQueueSubmit(GraphicsQueue.queue(), submitInfo, inFlightFences.get(currentFrame))) != VK_SUCCESS) {
                vkResetFences(device, stackGet().longs(inFlightFences.get(currentFrame)));
                throw new RuntimeException("Failed to submit draw command buffer: " + vkResult);
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            presentInfo.pWaitSemaphores(stackGet().longs(renderFinishedSemaphores.get(currentFrame)));

            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(Vulkan.getSwapChain().getId()));

            presentInfo.pImageIndices(stack.ints(imageIndex));

            vkResult = vkQueuePresentKHR(PresentQueue.queue(), presentInfo);

            if(vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                return;
            } else if(vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present swap chain image");
            }

            currentFrame = (currentFrame + 1) % framesNum;
        }
    }

    void waitForSwapChain()
    {
        vkResetFences(device, inFlightFences.get(currentFrame));

//        constexpr VkPipelineStageFlags t=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        try(MemoryStack stack = MemoryStack.stackPush()) {
            //Empty Submit
            VkSubmitInfo info = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));

            vkQueueSubmit(GraphicsQueue.queue(), info, inFlightFences.get(currentFrame));
            vkWaitForFences(device, inFlightFences.get(currentFrame),  true, -1);
        }
    }

    private void recreateSwapChain() {
        Vulkan.waitIdle();

        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));

        Vulkan.recreateSwapChain();

        //Semaphores need to be recreated in order to make them unsignaled
        destroySyncObjects();

        int newFramesNum = getSwapChain().getFramesNum();
        imagesNum = getSwapChain().getImagesNum();

        if(framesNum != newFramesNum) {
            AreaUploadManager.INSTANCE.waitAllUploads();

            framesNum = newFramesNum;
            MemoryManager.createInstance(newFramesNum);
            createStagingBuffers();
            allocateCommandBuffers();

            Pipeline.recreateDescriptorSets(framesNum);

            drawer.createResources(framesNum);
        }

        createSyncObjects();

        this.onResizeCallbacks.forEach(Runnable::run);

        currentFrame = 0;
    }

    public void cleanUpResources() {
        destroySyncObjects();

        drawer.cleanUpResources();
        tstFRAMEBUFFER_2.cleanUp();
        TerrainShaderManager.destroyPipelines();
        VTextureSelector.getWhiteTexture().free();
    }

    private void destroySyncObjects() {
        for (int i = 0; i < framesNum; ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
            vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
        }
    }

    public void setMainPass(MainPass mainPass) { this.mainPass = mainPass; }

    public void addOnResizeCallback(Runnable runnable) {
        this.onResizeCallbacks.add(runnable);
    }

    public void bindGraphicsPipeline(GraphicsPipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;

        PipelineState currentState = PipelineState.getCurrentPipelineState(tstFRAMEBUFFER_2.renderPass2);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(currentState));

        addUsedPipeline(pipeline);
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;
        pipeline.bindDescriptorSets(commandBuffer, currentFrame, true);
    }

    public void pushConstants(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;

        PushConstants pushConstants = pipeline.getPushConstants();

        try (MemoryStack stack = stackPush()) {
            ByteBuffer buffer = stack.malloc(pushConstants.getSize());
            long ptr = MemoryUtil.memAddress0(buffer);
            pushConstants.update(ptr);

            nvkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants.getSize(), ptr);
        }

    }

    public static void setDepthBias(float units, float factor) {
        VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

        vkCmdSetDepthBias(commandBuffer, units, 0.0f, factor);
    }

    public static void clearAttachments(int v) {
        clearAttachments(v, tstFRAMEBUFFER_2.width, tstFRAMEBUFFER_2.height);
    }

    public static void clearAttachments(int v, int width, int height) {
        if(skipRendering)
            return;

        try(MemoryStack stack = stackPush()) {
            //ClearValues have to be different for each attachment to clear, it seems it works like a buffer: color and depth attributes override themselves
            VkClearValue colorValue = VkClearValue.calloc(stack);
            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.calloc(stack);
            depthValue.depthStencil().set(VRenderSystem.clearDepth, 0); //Use fast depth clears if possible

            int attachmentsCount = v == (GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT) ? 2 : 1;
            final VkClearAttachment.Buffer pAttachments = VkClearAttachment.malloc(attachmentsCount, stack);
            switch (v) {
                case GL_DEPTH_BUFFER_BIT -> {

                    VkClearAttachment clearDepth = pAttachments.get(0);
                    clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepth.clearValue(depthValue);
                }
                case GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColor = pAttachments.get(0);
                    clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColor.colorAttachment(0);
                    clearColor.clearValue(colorValue);
                }
                case GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColor = pAttachments.get(0);
                    clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColor.colorAttachment(0);
                    clearColor.clearValue(colorValue);

                    VkClearAttachment clearDepth = pAttachments.get(1);
                    clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepth.clearValue(depthValue);
                }
                default -> throw new RuntimeException("unexpected value");
            }

            //Rect to clear
            VkRect2D renderArea = VkRect2D.malloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(width, height);

            VkClearRect.Buffer pRect = VkClearRect.malloc(1, stack);
            pRect.rect(renderArea);
            pRect.baseArrayLayer(0);
            pRect.layerCount(1);

            vkCmdClearAttachments(INSTANCE.currentCmdBuffer, pAttachments, pRect);
        }
    }

    public static void setViewport(int x, int y, int width, int height) {
        try(MemoryStack stack = stackPush()) {
            VkExtent2D transformedExtent = transformToExtent(VkExtent2D.malloc(stack), width, height);
            VkOffset2D transformedOffset = transformToOffset(VkOffset2D.malloc(stack), x, y, width, height);
            VkViewport.Buffer viewport = VkViewport.malloc(1, stack);

            x = transformedOffset.x();
            y = transformedOffset.y();
            width = transformedExtent.width();
            height = transformedExtent.height();

            viewport.x(x);
            viewport.y(height + y);
            viewport.width(width);
            viewport.height(-height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset(VkOffset2D.malloc(stack).set(0, 0));
            scissor.extent(transformedExtent);

            vkCmdSetViewport(INSTANCE.currentCmdBuffer, 0, viewport);
            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    /**
     * Transform the X/Y coordinates from Minecraft coordinate space to Vulkan coordinate space
     * and write them to VkOffset2D
     * @param offset2D the offset to which the coordinates should be written
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param w the viewport/scissor operation width
     * @param h the viewport/scissor operation height
     * @return same offset2D with transformations applied as necessary
     */
    private static VkOffset2D transformToOffset(VkOffset2D offset2D, int x, int y, int w, int h) {
        int pretransformFlags = Vulkan.getPretransformFlags();
        if(pretransformFlags == 0) {
            offset2D.set(x, y);
            return offset2D;
        }

        int framebufferWidth = tstFRAMEBUFFER_2.width;
        int framebufferHeight = tstFRAMEBUFFER_2.height;
        switch (pretransformFlags) {
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR -> {
                offset2D.x(framebufferWidth - h - y);
                offset2D.y(x);
            }
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR -> {
                offset2D.x(framebufferWidth - w - x);
                offset2D.y(framebufferHeight - h - y);
            }
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR -> {
                offset2D.x(y);
                offset2D.y(framebufferHeight - w - x);
            }
            default -> {
                offset2D.x(x);
                offset2D.y(y);
            }
        }
        return offset2D;
    }

    /**
     * Transform the width and height from Minecraft coordinate space to the Vulkan coordinate space
     * and write them to VkExtent2D
     * @param extent2D the extent to which the values should be written
     * @param w the viewport/scissor operation width
     * @param h the viewport/scissor operation height
     * @return the same VkExtent2D with transformations applied as necessary
     */
    private static VkExtent2D transformToExtent(VkExtent2D extent2D, int w, int h) {
        int pretransformFlags = Vulkan.getPretransformFlags();
        if(pretransformFlags == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR ||
                pretransformFlags == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR) {
            return extent2D.set(h, w);
        }
        return extent2D.set(w, h);
    }

    public static void setScissor(int x, int y, int width, int height) {
        try(MemoryStack stack = stackPush()) {

        	VkExtent2D extent = VkExtent2D.malloc(stack);

            // Since our x and y are still in Minecraft's coordinate space, pre-transform the framebuffer's width and height to get expected results.
            transformToExtent(extent, tstFRAMEBUFFER_2.width, tstFRAMEBUFFER_2.height);
            int framebufferHeight = extent.height();

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            // Use this corrected height to transform from OpenGL to Vulkan coordinate space.

            scissor.offset(transformToOffset(VkOffset2D.malloc(stack), x, framebufferHeight - (y + height), width, height));
            // Reuse the extent to transform the scissor width/height
            scissor.extent(transformToExtent(extent, width, height));


            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    public static void resetScissor() {

        try(MemoryStack stack = stackPush()) {
            VkRect2D.Buffer scissor = tstFRAMEBUFFER_2.scissor(stack);
            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    public static void pushDebugSection(String s) {
        if(Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            try(MemoryStack stack = stackPush()) {
                VkDebugUtilsLabelEXT markerInfo = VkDebugUtilsLabelEXT.calloc(stack);
                markerInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT);
                ByteBuffer string = stack.UTF8(s);
                markerInfo.pLabelName(string);
                vkCmdBeginDebugUtilsLabelEXT(commandBuffer, markerInfo);
            }
        }
    }

    public static void popDebugSection() {
        if(Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            vkCmdEndDebugUtilsLabelEXT(commandBuffer);
        }
    }

    public static void popPushDebugSection(String s) {
        popDebugSection();
        pushDebugSection(s);
    }

    public static int getFramesNum() { return INSTANCE.framesNum; }

    public static VkCommandBuffer getCommandBuffer() { return INSTANCE.currentCmdBuffer; }

    public static void scheduleSwapChainUpdate() { swapChainUpdate = true; }

    public void updateFrameBuffer() {
        vkDeviceWaitIdle(device); //Wait for prior cmdBuffer(s)
        tstFRAMEBUFFER_2.bindRenderPass(VRenderSystem.getDefaultRenderPassState());
        VRenderSystem.renderPassUpdate =false;
    }

}
