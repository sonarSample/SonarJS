import { CONSTANT } from "library/constants";

export function foo(value: string) {
  return value < CONSTANT; // Noncompliant: S3003
}
