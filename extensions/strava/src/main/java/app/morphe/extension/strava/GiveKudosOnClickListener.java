package app.morphe.extension.strava;

import android.view.View;

import java.lang.reflect.Method;

public final class GiveKudosOnClickListener implements View.OnClickListener {
    private final Object outerThis;
    private final Object actionSingleton;
    private final String handlerMethodName;

    public GiveKudosOnClickListener(Object outerThis, Object actionSingleton, String handlerMethodName) {
        this.outerThis = outerThis;
        this.actionSingleton = actionSingleton;
        this.handlerMethodName = handlerMethodName;
    }

    /**
     * Attaches a listener to the "Give Kudos" button.
     *
     * <p>Called instead of setting the listener in patched code, because the constructor
     * this is injected into has too few free registers to do so inline.
     * Using {@link View#setOnClickListener} also avoids depending on the button's own type,
     * which overrides that method to forward clicks into its Compose content.
     *
     * @param view              The "Give Kudos" button, or {@code null} if it is not in the layout.
     * @param outerThis         Instance declaring {@code handlerMethodName}.
     * @param actionSingleton   Singleton state that makes the handler show the "Give Kudos" dialog.
     * @param handlerMethodName Name of the method that handles the state.
     */
    public static void attach(View view, Object outerThis, Object actionSingleton, String handlerMethodName) {
        if (view == null) return;

        view.setOnClickListener(new GiveKudosOnClickListener(outerThis, actionSingleton, handlerMethodName));
    }

    @Override
    public void onClick(View v) {
        if (outerThis == null || actionSingleton == null || handlerMethodName == null) return;

        try {
            Method target = null;
            for (Method m : outerThis.getClass().getDeclaredMethods()) {
                if (!m.getName().equals(handlerMethodName)) continue;
                if (m.getParameterTypes().length != 1) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(actionSingleton.getClass())) continue;
                target = m;
                break;
            }
            if (target == null) return;

            target.setAccessible(true);
            target.invoke(outerThis, actionSingleton);
        } catch (Throwable ignored) {
            // Best-effort: if Strava internals change, avoid crashing the app.
        }
    }
}

