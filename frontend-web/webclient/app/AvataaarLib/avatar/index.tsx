import * as React from "react";

import Clothe from "./clothes";
import Face from "./face";
import {AvatarComponentProps, AvatarStyle} from "@/AvataaarLib";
import Top, {Accessories} from "./top";

export default function Avatar(props: AvatarComponentProps): React.ReactNode {
    const {avatarStyle} = props;
    const circle = avatarStyle === AvatarStyle.Circle;
    return (
        <svg
            style={props.style}
            width="264px"
            height="280px"
            viewBox="0 0 264 280"
            version="1.1"
            xmlns="http://www.w3.org/2000/svg"
            xmlnsXlink="http://www.w3.org/1999/xlink"
        >
            <desc>Created with getavataaars.com</desc>
            <defs>
                <circle id="path-1" cx="120" cy="120" r="120" />
                <path
                    d="M12,160 C12,226.27417 65.72583,280 132,280 C198.27417,280 252,226.27417 252,160 L264,160 L264,-1.42108547e-14 L-3.19744231e-14,-1.42108547e-14 L-3.19744231e-14,160 L12,160 Z"
                    id="path-3"
                />
                <path
                    d="M124,144.610951 L124,163 L128,163 L128,163 C167.764502,163 200,195.235498 200,235 L200,244 L0,244 L0,235 C-4.86974701e-15,195.235498 32.235498,163 72,163 L72,163 L76,163 L76,144.610951 C58.7626345,136.422372 46.3722246,119.687011 44.3051388,99.8812385 C38.4803105,99.0577866 34,94.0521096 34,88 L34,74 C34,68.0540074 38.3245733,63.1180731 44,62.1659169 L44,56 L44,56 C44,25.072054 69.072054,5.68137151e-15 100,0 L100,0 L100,0 C130.927946,-5.68137151e-15 156,25.072054 156,56 L156,62.1659169 C161.675427,63.1180731 166,68.0540074 166,74 L166,88 C166,94.0521096 161.51969,99.0577866 155.694861,99.8812385 C153.627775,119.687011 141.237365,136.422372 124,144.610951 Z"
                    id="path-5"
                />
            </defs>
            <g
                id="Avataaar"
                stroke="none"
                strokeWidth="1"
                fill="none"
                fillRule="evenodd"
            >
                <g
                    transform="translate(-825.000000, -1100.000000)"
                    id="Avataaar/Circle"
                >
                    <g transform="translate(825.000000, 1100.000000)">
                        {circle ? (
                            <g
                                id="Circle"
                                strokeWidth="1"
                                fillRule="evenodd"
                                transform="translate(12.000000, 40.000000)"
                            >
                                <mask id="mask-2" fill="white">
                                    <use xlinkHref="#path-1" />
                                </mask>
                                <use
                                    id="Circle-Background"
                                    fill="#E6E6E6"
                                    xlinkHref="#path-1"
                                />
                                <g
                                    id="Color/Palette/Blue-01"
                                    mask="url(#mask-2)"
                                    fill="#65C9FF"
                                >
                                    <rect id="🖍Color" x="0" y="0" width="240" height="240" />
                                </g>
                            </g>
                        ) : null}
                        {circle ? (
                            <mask id="mask-4" fill="white">
                                <use xlinkHref="#path-3" />
                            </mask>
                        ) : null}
                        <g id="Mask" />
                        <g
                            id="Avataaar"
                            strokeWidth="1"
                            fillRule="evenodd"
                            mask="url(#mask-4)"
                        >
                            <g id="Body" transform="translate(32.000000, 36.000000)">
                                <mask id="mask-6" fill="white">
                                    <use xlinkHref="#path-5" />
                                </mask>
                                <use fill="#D0C6AC" xlinkHref="#path-5" />
                                <Skin optionValue={props.skinColors} maskID="mask-6" />
                                <path
                                    d="M156,79 L156,102 C156,132.927946 130.927946,158 100,158 C69.072054,158 44,132.927946 44,102 L44,79 L44,94 C44,124.927946 69.072054,150 100,150 C130.927946,150 156,124.927946 156,94 L156,79 Z"
                                    id="Neck-Shadow"
                                    fillOpacity="0.100000001"
                                    fill="#000000"
                                    mask="url(#mask-6)"
                                />
                            </g>
                            <Clothe
                                optionValue={props.clothes}
                                color={props.colorFabric}
                                graphic={props.clothesGraphic}
                            />
                            <Face eyebrow={props.eyebrows} eyes={props.eyes} mouth={props.mouthTypes} />
                            <Top optionValue={props.top} {...props}>
                                <Accessories optionValue={props.topAccessory} />
                            </Top>
                        </g>
                    </g>
                </g>
            </g>
        </svg>
    );
}

import {SkinColors} from "@/UserSettings/AvatarOptions";

export interface Props {
    maskID: string;
    optionValue: SkinColors;
}

function makeColor(name: string, color: string) {
    function ColorComponent(props: Props) {
        return (
            <g
                id="Skin/👶🏽-03-Brown"
                mask={`url(#${props.maskID})`}
                fill={color}
            >
                <g transform="translate(0.000000, 0.000000)" id="Color">
                    <rect x="0" y="0" width="264" height="280" />
                </g>
            </g>
        );
    }
    const anyComponent = ColorComponent as any;
    anyComponent.displayName = name;
    anyComponent.optionValue = name;
    return anyComponent;
}

const Tanned = makeColor("Tanned", "#FD9841");
const Yellow = makeColor("Yellow", "#F8D25C");
const Pale = makeColor("Pale", "#FFDBB4");
const Light = makeColor("Light", "#EDB98A");
const Brown = makeColor("Brown", "#D08B5B");
const DarkBrown = makeColor("DarkBrown", "#AE5D29");
const Black = makeColor("Black", "#614335");

function Skin(props: Props): React.ReactNode {
    switch (props.optionValue) {
        case SkinColors.Black:
            return <Black maskID={props.maskID} />;
        case SkinColors.Brown:
            return <Brown maskID={props.maskID} />;
        case SkinColors.DarkBrown:
            return <DarkBrown maskID={props.maskID} />;
        case SkinColors.Light:
            return <Light maskID={props.maskID} />;
        case SkinColors.Pale:
            return <Pale maskID={props.maskID} />;
        case SkinColors.Tanned:
            return <Tanned maskID={props.maskID} />;
        case SkinColors.Yellow:
            return <Yellow maskID={props.maskID} />;
    }
}
