import * as React from "react";
import {classConcat, injectStyle} from "@/Unstyled";
import {useCallback, useEffect, useRef} from "react";
import {ThemeColor} from "./theme";

interface ToggleProps {
    checked: boolean;
    onChange: (prevValue: boolean) => void;
    activeColor?: ThemeColor;
    inactiveColor?: ThemeColor;
    circleColor?: ThemeColor;
    colorAnimationDisabled?: boolean;
}

export const Toggle: React.FC<ToggleProps> = ({
    checked,
    onChange,
    activeColor = "successMain",
    inactiveColor = "textSecondary",
    circleColor = "fixedWhite",
    colorAnimationDisabled = false
}) => {
    const checkedRef = useRef(checked);
    useEffect(() => {
        checkedRef.current = checked;
    }, [checked]);

    const handler = useCallback((e: React.SyntheticEvent) => {
        e.stopPropagation();
        e.preventDefault();
        onChange(checkedRef.current);
    }, [onChange]);

    const style: React.CSSProperties = {};
    style["--inactiveColor"] = `var(--${inactiveColor})`;
    style["--activeColor"] = `var(--${activeColor})`;
    style["--circleColor"] = `var(--${circleColor})`

    return <div
        onClick={handler}
        style={style}
        data-active={checked}
        className={classConcat(ToggleWrapperClass, colorAnimationDisabled ? "color-anim-disabled" : undefined)}
    >
        <div />
    </div>
}

const ToggleWrapperClass = injectStyle("toggle-wrapper", k => `
    ${k} {
        --inactiveColor: #ff0;
        --activeColor: #f0f;
        --circleColor: #0ff
    }

    ${k} {
        border-radius: 12px;
        height: 26px;
        width: 45px;
        background-color: var(--inactiveColor);
        transition: 0.2s all;
        padding-top: 2px;
        padding-left: 2px;
        cursor: pointer;
    }

    ${k}[data-active="true"] {
        background-color: var(--activeColor);
        padding-left: 21px;
    }

    ${k} > div {
        border-radius: 50%;
        width: 22px;
        background-color: var(--circleColor);
        animation: background-color 0.2;
        height: 22px;
    }
    
    ${k}.color-anim-disabled {
        transition: 0.2s padding;
    }
    
    ${k}.color-anim-disabled > div {
        animation: unset;
    }
`);
