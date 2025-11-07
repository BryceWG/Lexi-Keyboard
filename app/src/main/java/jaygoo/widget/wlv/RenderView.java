package jaygoo.widget.wlv;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 封装的SurfaceView（来自 dev_docs/WaveLineView/library），用于波形渲染线程管理。
 */
public abstract class RenderView extends SurfaceView implements SurfaceHolder.Callback {

    // 是否正在绘制动画
    private boolean isStartAnim = false;
    private final static Object surfaceLock = new Object();
    private RenderThread renderThread;

    /**
     * 绘制背景，防止开始时黑屏
     */
    protected abstract void doDrawBackground(Canvas canvas);

    /**
     * 渲染surfaceView的回调方法。
     *
     * @param canvas 画布
     */
    protected abstract void onRender(Canvas canvas, long millisPassed);

    public RenderView(Context context) {
        this(context, null);
    }

    public RenderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getHolder().addCallback(this);
    }

    /* 回调/线程 */
    private static class RenderThread extends Thread {

        private static final long SLEEP_TIME = 16;
        private WeakReference<RenderView> renderView;
        private boolean running = false;
        private boolean destoryed = false;
        private boolean isPause = false;

        public RenderThread(RenderView renderView) {
            super("RenderThread");
            this.renderView = new WeakReference<>(renderView);
        }

        private SurfaceHolder getSurfaceHolder() {
            if (getRenderView() != null) {
                return getRenderView().getHolder();
            }
            return null;
        }

        private RenderView getRenderView() {
            return renderView.get();
        }

        @Override
        public void run() {
            long startAt = System.currentTimeMillis();
            while (!destoryed) {
                synchronized (surfaceLock) {

                    // 这里并没有真正的结束Thread，防止部分手机连续调用同一Thread出错
                    while (isPause) {
                        try {
                            surfaceLock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    if (running) {
                        if (getSurfaceHolder() != null && getRenderView() != null) {
                            Canvas canvas = getSurfaceHolder().lockCanvas();
                            if (canvas != null) {
                                getRenderView().doDrawBackground(canvas);
                                if (getRenderView().isStartAnim) {
                                    getRenderView().render(canvas, System.currentTimeMillis() - startAt);
                                }
                                getSurfaceHolder().unlockCanvasAndPost(canvas);
                            }
                        } else {
                            running = false;
                        }

                    }

                }
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }

        }

        public void setRun(boolean isRun) {
            this.running = isRun;
        }

    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderer = onCreateRenderer();
        if (renderer != null && renderer.isEmpty()) {
            throw new IllegalStateException();
        }

        renderThread = new RenderThread(this);
        // 如果在 surface 创建前已经调用过 startAnim，则此处应立即启动渲染线程
        if (isStartAnim) {
            startThread();
        }
    }

    /**
     * 解锁暂停，继续执行绘制任务
     * 默认当Resume时不自动启动动画
     */
    public void onResume() {
        synchronized (surfaceLock) {
            if (renderThread != null) {
                renderThread.isPause = false;
                surfaceLock.notifyAll();
            }
        }
    }

    // 假暂停，并没有结束Thread
    public void onPause() {
        synchronized (surfaceLock) {
            if (renderThread != null) {
                renderThread.isPause = true;
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 这里可以获取SurfaceView的宽高等信息
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (surfaceLock) {  // 这里需要加锁，否则doDraw中有可能会crash
            renderThread.setRun(false);
            renderThread.destoryed = true;
        }
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus && isStartAnim) {
            startAnim();
        } else {
            startThread();
        }
    }

    /* 绘图 */
    public interface IRenderer {
        void onRender(Canvas canvas, long millisPassed);
    }

    private List<IRenderer> renderer;

    protected List<IRenderer> onCreateRenderer() {
        return null;
    }

    private void render(Canvas canvas, long millisPassed) {
        if (renderer != null) {
            for (int i = 0, size = renderer.size(); i < size; i++) {
                renderer.get(i).onRender(canvas, millisPassed);
            }
        } else {
            onRender(canvas, millisPassed);
        }
    }

    public void startAnim() {
        isStartAnim = true;
        startThread();
    }

    private void startThread() {
        if (renderThread != null && !renderThread.running) {
            renderThread.setRun(true);
            try {
                if (renderThread.getState() == Thread.State.NEW) {
                    renderThread.start();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void stopAnim() {
        isStartAnim = false;
        if (renderThread != null && renderThread.running) {
            renderThread.setRun(false);
            renderThread.interrupt();
        }
    }

    public boolean isRunning() {
        if (renderThread != null) {
            return renderThread.running;
        }
        return false;
    }

    // 释放相关资源，防止内存泄漏
    public void release() {
        if (getHolder() != null && getHolder().getSurface() != null) {
            getHolder().getSurface().release();
            getHolder().removeCallback(this);
        }
    }
}
