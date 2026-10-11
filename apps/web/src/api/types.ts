import type { components } from "./schema";

type Schemas = components["schemas"];

export type Recording = Schemas["RecordingSummary"];
export type Conversation = Schemas["ConversationSummary"];
export type ConversationDetail = Schemas["ConversationDetail"];
export type DayView = Schemas["DayView"];
export type Digest = Schemas["DigestView"];
export type DigestMoment = Schemas["DigestMoment"];
export type Item = Schemas["ItemView"];
export type Segment = Schemas["SegmentView"];
export type Speaker = Schemas["SpeakerView"];
export type AskTurn = Schemas["AskView"];
export type ReviewItem = Schemas["ReviewView"];
export type ReviewState = ReviewItem["state"];
export type SearchResult = Schemas["SearchResult"];
export type SearchHit = Schemas["SearchHit"];
export type Word = Schemas["WordView"];
export type Update = Schemas["UpdateView"];
export type Sync = Schemas["SyncView"];
export type Me = Schemas["Me"];
