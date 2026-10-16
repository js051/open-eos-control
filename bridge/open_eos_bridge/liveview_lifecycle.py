"""Necessary source publication/fallback guards, not proof of frame I/O."""
def evaluate(value: dict) -> dict[str, bool]:
    alive = (value.get('sameOwner') is True and value.get('sameAttempt') is True
             and value.get('cancelled') is not True and value.get('retired') is not True
             and (value.get('foregroundRequired') is not True or value.get('foreground') is True))
    admitted = value.get('startAdmitted') is True
    stop = value.get('stopEvidence')
    terminal = value.get('terminal') is True
    evidence = value.get('frameEvidence')
    level = {'encoded-frame': 1, 'decoded-frame': 2, 'presented-frame': 3}.get(
        evidence if isinstance(evidence, str) else '', 0)
    ready = alive and admitted and value.get('startAcknowledged') is True and not terminal and stop == 'pending'
    return {'canPublishInputReady': ready and level >= 1, 'canPublishDecoded': ready and level >= 2,
            'canPublishPresented': ready and level >= 3,
            'canFallback': alive and terminal and value.get('sourceMode') == 'AUTO'
            and (stop == 'confirmed' or (not admitted and stop == 'not-needed')),
            'stopRequired': admitted and stop != 'confirmed'}
