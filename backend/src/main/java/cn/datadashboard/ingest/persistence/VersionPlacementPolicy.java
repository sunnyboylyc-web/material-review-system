package cn.datadashboard.ingest.persistence;

import org.springframework.stereotype.Component;

@Component
final class VersionPlacementPolicy {

    VersionPlacement decide(DayVersionPointers pointers) {
        if (pointers.currentVersionId() == null && pointers.previousVersionId() != null) {
            throw new IllegalStateException("Previous version cannot exist without a current version");
        }
        if (pointers.currentVersionId() == null) {
            return VersionPlacement.FIRST_CURRENT;
        }
        if (pointers.previousVersionId() == null) {
            return VersionPlacement.REPLACE_CURRENT;
        }
        return VersionPlacement.ISOLATED;
    }
}
