/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import clsx from "clsx";
import type { ReactNode } from "react";
import type { ChatMessage } from "./types";
import * as classes from "./AIChatbot.module.less";

type MessageActionBarProps = {
  message: ChatMessage;
  onCopy: () => void;
  showRegenerate?: boolean;
  onRegenerate?: () => void;
  regenerateDisabled?: boolean;
  onFeedback?: (feedback: "up" | "down") => void;
  onEditPrompt?: () => void;
};

const IconActionButton = ({
  label,
  onClick,
  disabled,
  active,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  active?: boolean;
  children: ReactNode;
}) => (
  <button
    type="button"
    className={clsx(
      classes.iconActionBtn,
      active && classes.iconActionBtnActive,
    )}
    aria-label={label}
    title={label}
    onClick={onClick}
    disabled={disabled}
  >
    {children}
  </button>
);

const CopyIcon = () => (
  <svg className={classes.iconActionSvg} viewBox="0 0 24 24" aria-hidden>
    <path
      fill="currentColor"
      d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
    />
  </svg>
);

const RegenerateIcon = () => (
  <svg className={classes.iconActionSvg} viewBox="0 0 24 24" aria-hidden>
    <path
      fill="currentColor"
      d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08a5.99 5.99 0 0 1-5.65 4c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
    />
  </svg>
);

const ThumbUpIcon = () => (
  <svg className={classes.iconActionSvg} viewBox="0 0 24 24" aria-hidden>
    <path
      fill="currentColor"
      d="M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"
    />
  </svg>
);

const ThumbDownIcon = () => (
  <svg className={classes.iconActionSvg} viewBox="0 0 24 24" aria-hidden>
    <path
      fill="currentColor"
      d="M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z"
    />
  </svg>
);

const EditIcon = () => (
  <svg className={classes.iconActionSvg} viewBox="0 0 24 24" aria-hidden>
    <path
      fill="currentColor"
      d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1.003 1.003 0 0 0 0-1.42l-2.34-2.34a1.003 1.003 0 0 0-1.42 0l-1.83 1.83 3.75 3.75 1.84-1.82z"
    />
  </svg>
);

export const MessageActionBar = ({
  message,
  onCopy,
  showRegenerate,
  onRegenerate,
  regenerateDisabled,
  onFeedback,
  onEditPrompt,
}: MessageActionBarProps) => {
  const isAssistant = message.role === "assistant";

  return (
    <div className={classes.messageActionBar}>
      <IconActionButton label="Sao chép" onClick={onCopy}>
        <CopyIcon />
      </IconActionButton>

      {isAssistant && showRegenerate && onRegenerate && (
        <IconActionButton
          label="Tạo lại"
          onClick={onRegenerate}
          disabled={regenerateDisabled}
        >
          <RegenerateIcon />
        </IconActionButton>
      )}

      {isAssistant && onFeedback && (
        <>
          <IconActionButton
            label="Hữu ích"
            onClick={() => onFeedback("up")}
            active={message.feedback === "up"}
          >
            <ThumbUpIcon />
          </IconActionButton>
          <IconActionButton
            label="Không hữu ích"
            onClick={() => onFeedback("down")}
            active={message.feedback === "down"}
          >
            <ThumbDownIcon />
          </IconActionButton>
        </>
      )}

      {!isAssistant && onEditPrompt && (
        <IconActionButton label="Sửa câu hỏi" onClick={onEditPrompt}>
          <EditIcon />
        </IconActionButton>
      )}
    </div>
  );
};
