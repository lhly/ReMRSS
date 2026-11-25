package top.zainzh.rearscreen;

import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

public class RearAnimationDisplayTest {
    @Test public void coveredPhysicalCastIsRestorableOnlyOnTheSamePanelWithTheSamePackage() throws Exception {
        ITaskService service = (ITaskService) Proxy.newProxyInstance(
                ITaskService.class.getClassLoader(), new Class<?>[] { ITaskService.class },
                (instance, method, args) -> {
                    if ("isTaskOnDisplay".equals(method.getName())) return (int) args[0] == 61510 && (int) args[1] == 1;
                    if ("getPackageNameFromTaskId".equals(method.getName())) return "com.android.settings";
                    throw new AssertionError(method.getName());
                });
        assertEquals(61510, RearAnimationDisplay.exactPhysicalReturnTask(service, "com.android.settings:61510", 1));
        assertEquals(-1, RearAnimationDisplay.exactPhysicalReturnTask(service, "com.android.settings:61510", 2));
        assertEquals(-1, RearAnimationDisplay.exactPhysicalReturnTask(service, "example.other:61510", 1));
        assertEquals(-1, RearAnimationDisplay.exactPhysicalReturnTask(service, "com.android.settings:61511", 1));
        assertEquals(-1, RearAnimationDisplay.exactPhysicalReturnTask(service, null, 1));
    }
}
