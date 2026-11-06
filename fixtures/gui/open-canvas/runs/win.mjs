import { exec, sleep } from './lib.mjs';
export async function setWindow(w, h) {
  exec(`def hd = mc.getWindow().handle(); mc.execute({ org.lwjgl.glfw.GLFW.glfwSetWindowSize(hd, ${w}, ${h}) } as Runnable); return 'q'`);
  await sleep(1500);
  const r = exec(`mc.resizeGui(); return mc.getWindow().getWidth()+'x'+mc.getWindow().getHeight()`);
  return r;
}
