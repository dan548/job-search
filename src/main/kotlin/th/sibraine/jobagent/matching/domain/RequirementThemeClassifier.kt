package th.sibraine.jobagent.matching.domain

data class RequirementTheme(
    val key: String,
    val title: String,
    val preference: Boolean = false,
    val canonical: Boolean = true,
)

class RequirementThemeClassifier {
    fun classify(requirement: String): RequirementTheme {
        val value = requirement.lowercase()
        return when {
            value.containsAny("remote", "contractor", "onsite", "on-site", "hybrid", "формат работы") ->
                RequirementTheme("work-format", "Формат работы", preference = true)
            value.containsAny(
                "tbilisi", "belgrade", "lisbon", "madrid", "riga", "tallinn", "valencia", "yerevan",
                "location", "relocat", "локация", "переезд",
            ) -> RequirementTheme("location", "Локация и переезд", preference = true)
            value.containsAny("sponsor", "visa", "work authorization", "right to work", "разрешение на работу") ->
                RequirementTheme("work-authorization", "Разрешение на работу", preference = true)
            value.containsAny(
                "kotlin", "java", "jvm", "gradle", "spring", "dagger", "junit", "apache commons",
                "dependency management", "modular build",
            ) ->
                RequirementTheme("jvm", "Java, Kotlin и экосистема JVM")
            value.containsAny("python", "blender", "3d", "2d", "opengl", "webgl", "raytrac", "computer vision", "geometry") ->
                RequirementTheme("python-3d", "Python, Blender и 2D/3D-технологии")
            value.containsAny("c++", " c ", "native development") -> RequirementTheme("native-languages", "C и C++")
            value.containsAny("sql", "sqlite", "mysql", "postgres") -> RequirementTheme("databases", "SQL и базы данных")
            value.containsAny(
                "solid", "clean architecture", "maintainable", "object-oriented", "automated test", "critical logic",
                "reliable code", "high-performance code", "design pattern", "code design", "quality code",
                "modular code", "debugging", "profiling", "logging", "performance optimization",
                "архитектура", "качество кода", "тестирование",
            ) -> RequirementTheme("code-quality", "Архитектура, качество кода и тестирование")
            value.containsAny("linux", "docker", "kubernetes") -> RequirementTheme("infrastructure", "Linux и инфраструктура")
            value.containsAny("english", "англий") -> RequirementTheme("english", "Английский язык")
            value.containsAny("math", "algorithm", "data-heavy", "математ", "алгоритм") ->
                RequirementTheme("algorithms", "Математика и алгоритмические задачи")
            else -> RequirementTheme("requirement:${normalize(requirement)}", requirement, canonical = false)
        }
    }

    fun normalize(value: String): String = value.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}+#]+"), "-")

    private fun String.containsAny(vararg terms: String) = terms.any(::contains)
}
