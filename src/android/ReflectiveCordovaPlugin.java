package by.chemerisuk.cordova.support;

import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.HashMap;


public class ReflectiveCordovaPlugin extends CordovaPlugin {
    private static String TAG = "ReflectiveCordovaPlugin";
    private Map<String, ActionCommandFactory> factories;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (factories == null) {
            factories = createCommandFactories(this.getClass());
        }

        ActionCommandFactory factory = factories.get(action);
        if (factory != null) {
            Runnable command = factory.create(this, args, callbackContext);
            if (factory.ui) {
                cordova.getActivity().runOnUiThread(command);
            } else if (factory.async) {
                cordova.getThreadPool().execute(command);
            } else {
                command.run();
            }
            return true;
        }

        return false;
    }

    private static Map<String, ActionCommandFactory> createCommandFactories(Class clazz) {
        Map<String, ActionCommandFactory> result = new HashMap<String, ActionCommandFactory>();
        for (Method method : clazz.getDeclaredMethods()) {
            CordovaMethod cordovaMethod = method.getAnnotation(CordovaMethod.class);
            if (cordovaMethod != null) {
                Class[] paramTypes = method.getParameterTypes();
                if (!CallbackContext.class.equals(paramTypes[paramTypes.length - 1])) {
                    LOG.e(TAG, "Method with @CordovaMethod must have CallbackContext as the last parameter");
                } else {
                    String methodAction = cordovaMethod.action();
                    if (methodAction.isEmpty()) {
                        methodAction = method.getName();
                    }
                    result.put(methodAction, new ActionCommandFactory(method));
                    // suppress Java language access checks
                    // to improve performance of future calls
                    method.setAccessible(true);
                }
            }
        }

        return result;
    }

    private static String getFullMethodName(CordovaPlugin plugin, Method method) {
        return plugin.getClass().getSimpleName() + "#" + method.getName();
    }

    private static class ActionCommandFactory {
        private final Method method;
        private final boolean async;
        private final boolean ui;

        public ActionCommandFactory(Method method) {
            this.method = method;
            this.async = method.getAnnotation(WorkerThread.class) != null;
            this.ui = method.getAnnotation(UiThread.class) != null;
        }

        public Runnable create(final CordovaPlugin plugin, JSONArray args, final CallbackContext callbackContext) throws JSONException {
            final Object[] methodArgs = getMethodArgs(args, callbackContext);
            // always create a new command object
            // to avoid possible thread conflicts
            return new Runnable() {
                @Override
                public void run() {
                    try {
                        method.invoke(plugin, methodArgs);
                    } catch (InvocationTargetException e) {
                        LOG.e(TAG, "Invocation exception at " + getFullMethodName(plugin, method), e.getTargetException());
                        callbackContext.error(e.getTargetException().getMessage());
                    } catch (Exception e) {
                        LOG.e(TAG, "Uncaught exception at " + getFullMethodName(plugin, method), e);
                        callbackContext.error(e.getMessage());
                    }
                }
            };
        }

        private static Object[] getMethodArgs(JSONArray args, CallbackContext callbackContext) throws JSONException {
            int len = args.length();
            Object[] methodArgs = new Object[len + 1];
            for (int i = 0; i < len; ++i) {
                Object argValue = args.opt(i);
                if (JSONObject.NULL.equals(argValue)) {
                    argValue = null;
                }
                methodArgs[i] = argValue;
            }
            // CallbackContext is always the last one
            methodArgs[len] = callbackContext;

            return methodArgs;
        }
    }
}
