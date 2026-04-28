1.  **Understand current state:**
    Currently, the entrance animations for `sidebarSlideX` and `sidebarSurfaceAlpha` in `MainActivity.kt` use a duration of 205ms and 135ms with `FastOutSlowInEasing`. There is no specific M3 Expressive entry motion guidelines implemented, nor is there a separate exit motion.
2.  **Define new parameters:**
    According to user requirements, we need M3 Expressive motion guidelines.
    For entrance (expand/slide in): Duration should be around 400ms. We will use `FastOutSlowInEasing`.
    For exit (collapse/slide out): The duration should be 25% shorter than entrance (400 * 0.75 = 300ms). We need an M3 exit easing, like `FastOutLinearInEasing`.
3.  **Import `FastOutLinearInEasing`**:
    Add `import androidx.compose.animation.core.FastOutLinearInEasing` if not present.
4.  **Modify `sidebarSlideX` and `sidebarSurfaceAlpha` definitions**:
    Change the `animationSpec` to conditionally use different tweens for entrance and exit based on the target value `sidebarVisible`.
5.  **Run Tests and Verifications**:
    Run `./gradlew app:compileFullDebugKotlin` to verify the syntax.
    Call `pre_commit_instructions`.
6.  **Submit changes.**
