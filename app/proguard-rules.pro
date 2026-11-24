# ============================================================================
# InkNote ProGuard / R8 规则
#
# 当前 release 没开混淆（isMinifyEnabled = false），这个文件先占位。
# 以后要开混淆时，注意手写引擎里有几处需要保护：
#   - 自定义 View 的 (Context, AttributeSet) 构造函数（XML 反射实例化用）
#   - 实现 Parcelable / Serializable 的笔迹模型（进程恢复用）
# ============================================================================

# 自定义 View 的构造函数不能被裁掉
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 保留行号，方便崩溃时定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
