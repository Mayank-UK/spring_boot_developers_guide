package guide.domain.environments;

import java.util.List;

/**
 * The active Spring profiles, computed ONCE at the composition root (which
 * owns the Spring {@code Environment}) and handed to the domain as a plain
 * value — the domain layer never learns Spring. Empty-active-profiles is
 * reported as the literal name "default", which is what Spring calls the
 * no-profile case; the inspector's reading lines teach the difference.
 */
public record ActiveProfiles(List<String> names) {

    public ActiveProfiles {
        names = names == null || names.isEmpty() ? List.of("default") : List.copyOf(names);
    }
}
